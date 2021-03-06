package org.to2mbn.maptranslator.impl.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ForkJoinPool;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.to2mbn.maptranslator.data.DataDescriptor;
import org.to2mbn.maptranslator.data.DataDescriptorGroup;
import org.to2mbn.maptranslator.process.AbstractReplacer;
import org.to2mbn.maptranslator.process.CommandParsingException;
import org.to2mbn.maptranslator.process.IteratorArgument;
import org.to2mbn.maptranslator.process.NodeParsingException;
import org.to2mbn.maptranslator.process.NodeReplacer;
import org.to2mbn.maptranslator.process.TextParsingException;
import org.to2mbn.maptranslator.process.TreeIterator;
import org.to2mbn.maptranslator.rules.RulesConstants;
import org.to2mbn.maptranslator.rules.RulesFactory;
import org.to2mbn.maptranslator.rules.RulesProvider;
import org.to2mbn.maptranslator.tree.DataStoreNode;
import org.to2mbn.maptranslator.tree.Node;
import org.to2mbn.maptranslator.tree.TextNode;

class MapHandlerImpl implements MapHandler {

	private static final Logger LOGGER = Logger.getLogger(MapHandlerImpl.class.getCanonicalName());

	public static CompletableFuture<MapHandler> create(Path dir) {
		return new MapHandlerImpl(dir).init();
	}

	private Path dir;
	private DataDescriptorGroup desGroup;
	private List<String> excludes = new Vector<>();
	private Map<String, StringMismatchWarning> stringMismatches = new ConcurrentSkipListMap<>();
	private Map<String, ResolveFailedWarning> resolveFailures = new ConcurrentSkipListMap<>();
	private IteratorArgument mapResolvingArgument;
	private ForkJoinPool pool = new ForkJoinPool(4 * Runtime.getRuntime().availableProcessors());
	private Set<BiConsumer<Node, Map<String, Object>>> parsingWarningMetadataProviders = Collections.synchronizedSet(new LinkedHashSet<>());

	public MapHandlerImpl(Path dir) {
		this.dir = dir;
		RulesProvider rules = RulesFactory.getGlobalProvider();
		mapResolvingArgument = new IteratorArgument();
		mapResolvingArgument.markers.addAll(rules.getTagMarkers());
		mapResolvingArgument.replacers.addAll(rules.getNodeReplacers());
	}

	private CompletableFuture<MapHandler> init() {
		return CompletableFuture.runAsync(() -> desGroup = DataDescriptorGroup.createFromFiles(dir))
				.thenApply(dummy -> this);
	}

	@Override
	public CompletableFuture<Map<String, List<String[]>>> extractStrings() {

		class PartResult {

			Map<String, List<String[]>> extracted;
			String nodeName;

			PartResult(Map<String, List<String[]>> extracted, String nodeName) {
				this.extracted = extracted;
				this.nodeName = nodeName;
			}
		}

		return CompletableFuture.supplyAsync(() -> {
			Predicate<String> excluder = createTextExcluder();
			clearLastWarnings();
			Map<String, List<String[]>> result = new LinkedHashMap<>();
			desGroup.read(node -> {
				AbstractReplacer.redirectParsingExceptions(() -> resolveMap(node),
						failure -> createResolveFailedWarning(failure).ifPresent(warn -> resolveFailures.put(warn.path, warn)));
				computeStringMismatches(node);
				return new PartResult(extractStrings(node, excluder), node.toString());
			})
					.sorted(Comparator.comparing(o -> o.nodeName))
					.forEachOrdered(partResult -> {
						partResult.extracted.forEach((k, v) -> {
							List<String[]> list = result.get(k);
							if (list == null) {
								result.put(k, v);
							} else {
								list.addAll(v);
							}
						});
					});
			return result;
		}, pool);
	}

	@Override
	public CompletableFuture<Void> replace(Map<String, String> table) {
		return CompletableFuture.runAsync(() -> {
			IteratorArgument arg = createReplacingArgument(table);
			desGroup.write(node -> {
				resolveMap(node);
				TreeIterator.iterate(arg, node);
			});
		}, pool);
	}

	@Override
	public CompletableFuture<Optional<Node>> resolveNode(String[] path) {
		return CompletableFuture.supplyAsync(() -> {
			String despName = path[0];
			for (DataDescriptor desp : desGroup.descriptors) {
				if (desp.toString().equals(despName)) {
					return resolveNode(path, desp);
				}
			}
			return Optional.empty();
		}, pool);
	}

	@Override
	public CompletableFuture<Optional<Node>> resolveNode(String argPath) {
		return CompletableFuture.supplyAsync(() -> {
			String path = argPath.trim();
			if (path.startsWith("/")) path = path.substring(1);
			for (DataDescriptor desp : desGroup.descriptors) {
				String despName = desp.toString();
				if (path.startsWith(despName)) {
					String[] split = path.substring(despName.length()).split("/");
					int splitLen = split.length;
					int splitPos = 0;
					if (splitLen > 0 && split[splitLen - 1].isEmpty()) splitLen--;
					if (splitLen > 0 && split[0].isEmpty()) {
						splitLen--;
						splitPos++;
					}
					String[] pathArray = new String[splitLen + 1];
					System.arraycopy(split, splitPos, pathArray, 1, splitLen);
					pathArray[0] = despName;
					return resolveNode(pathArray, desp);
				}
			}
			return Optional.empty();
		});
	}

	@Override
	public CompletableFuture<Void> saveNode(Node node) {
		return CompletableFuture.runAsync(() -> {
			Node root = node.root();
			if (!(root instanceof DataStoreNode)) {
				throw new IllegalArgumentException("Unexpected root node: " + root);
			}
			((DataStoreNode) root).write();
		});
	}

	private Optional<Node> resolveNode(String[] path, DataDescriptor desp) {
		DataStoreNode root = desp.createNode();
		try {
			root.read();
		} catch (Exception e) {
			LOGGER.log(Level.WARNING, "Couldn't read " + desp, e);
			return Optional.empty();
		}
		AbstractReplacer.redirectParsingExceptions(() -> resolveMap(root),
				failure -> createResolveFailedWarning(failure).ifPresent(warn -> failure.getNode().properties().put("resolve_failure.post", warn)));
		Optional<Node> result = root.resolve(path, 1);
		if (result.isPresent()) {
			return result;
		} else {
			root.close();
			return Optional.empty();
		}
	}

	private void resolveMap(Node node) {
		TreeIterator.iterate(mapResolvingArgument, node);
	}

	private void computeStringMismatches(Node root) {
		root.travel(node -> {
			if (node.properties().containsKey("origin")) {
				node.getText().ifPresent(current -> {
					String origin = (String) node.properties().get("origin");
					if (!origin.equals(current)) {
						StringMismatchWarning mismatch = new StringMismatchWarning(node, origin, current);
						stringMismatches.put(mismatch.path, fillParsingWarning(mismatch, node));
					}
				});
			}
		});
	}

	private Map<String, List<String[]>> extractStrings(Node root, Predicate<String> excluder) {
		Map<String, List<String[]>> result = new LinkedHashMap<>();
		root.travel(node -> {
			if (node.hasTag(RulesConstants.translatable)) {
				node.getText().ifPresent(text -> {
					if (!text.trim().isEmpty()) {
						List<String[]> g = result.get(text);
						if (g != null) {
							g.add(node.getPathArray());
						} else {
							if (!excluder.test(text)) {
								g = new ArrayList<>();
								g.add(node.getPathArray());
								result.put(text, g);
							}
						}
					}
				});
			}
		});
		return result;
	}

	private IteratorArgument createReplacingArgument(Map<String, String> table) {
		Predicate<String> excluder = createTextExcluder();
		IteratorArgument arg = new IteratorArgument();
		arg.replacers.add(new NodeReplacer(
				node -> {
					if (node.unmodifiableChildren().isEmpty() && node.hasTag(RulesConstants.translatable)) {
						Optional<String> optionalText = node.getText();
						if (optionalText.isPresent()) {
							String text = optionalText.get();
							return table.containsKey(text) && !excluder.test(text);
						}
					}
					return false;
				},
				node -> {
					String replacement = table.get(node.getText().get());
					return ((TextNode) node).replaceNodeText(() -> replacement);
				}));
		return arg;
	}

	private Predicate<String> createTextExcluder() {
		Pattern[] patterns = new Pattern[excludes.size()];
		for (int i = 0; i < patterns.length; i++) {
			patterns[i] = Pattern.compile(excludes.get(i));
		}
		return text -> {
			for (Pattern p : patterns) {
				if (p.matcher(text).matches()) {
					return true;
				}
			}
			return false;
		};
	}

	private Optional<ResolveFailedWarning> createResolveFailedWarning(NodeParsingException failure) {
		if (failure instanceof TextParsingException) {
			return Optional.of(
					fillParsingWarning(
							new ResolveFailedWarning(
									failure.getNode(),
									((TextParsingException) failure).getText(),
									failure instanceof CommandParsingException ? ((CommandParsingException) failure).getArguments() : null,
									failure.getCause()),
							failure.getNode()));
		} else {
			return Optional.empty();
		}
	}

	private <T extends ParsingWarning> T fillParsingWarning(T in, Node node) {
		parsingWarningMetadataProviders.forEach(handler -> handler.accept(node, in.metadata));
		return in;
	}

	@Override
	public CompletableFuture<Void> close() {
		return CompletableFuture.runAsync(() -> desGroup.close());
	}

	@Override
	public List<String> excludes() {
		return excludes;
	}

	@Override
	public List<ParsingWarning> lastParsingWarnings() {
		List<ParsingWarning> result = new ArrayList<>();
		result.addAll(resolveFailures.values());
		result.addAll(stringMismatches.values());
		return result;
	}

	private void clearLastWarnings() {
		stringMismatches.clear();
		resolveFailures.clear();
	}

	@Override
	public long currentProgress() {
		return desGroup.processed.get();
	}

	@Override
	public long totalProgress() {
		return desGroup.descriptors.size();
	}

	@Override
	public Set<BiConsumer<Node, Map<String, Object>>> parsingWarningMetadataProviders() {
		return parsingWarningMetadataProviders;
	}

}
