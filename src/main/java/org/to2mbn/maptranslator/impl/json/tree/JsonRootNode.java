package org.to2mbn.maptranslator.impl.json.tree;

import org.to2mbn.maptranslator.impl.json.parse.JSONArray;
import org.to2mbn.maptranslator.impl.json.parse.JSONException;
import org.to2mbn.maptranslator.impl.json.parse.JSONObject;
import org.to2mbn.maptranslator.tree.ArgumentNode;

public class JsonRootNode extends JsonNode implements ArgumentNode {

	public JsonRootNode(Object json) {
		super(json);
	}

	@Override
	public String toString() {
		return "<json>";
	}

	@Override
	public String toArgumentString() {
		String algorithm = (String) properties().get("json.to_string.algorithm");
		if (algorithm != null) {
			switch (algorithm) {
				case "gson":
					return gsonToString();

				case "format":
					if (json instanceof JSONObject) {
						return ((JSONObject) json).toString(4);
					} else if (json instanceof JSONArray) {
						return ((JSONArray) json).toString(4);
					} else {
						return json.toString();
					}
			}
		}
		return json.toString();
	}

	private String gsonToString() {
		String parsed = json.toString();
		Object resolved;
		try {
			resolved = new JSONObject(parsed);
		} catch (JSONException e) {
			try {
				resolved = new JSONArray(parsed);
			} catch (JSONException e1) {
				return parsed;
			}
		}
		return JSONObject._use_gson_toString(resolved::toString);
	}

	@Override
	public String getStringValue() {
		return toArgumentString();
	}

}
