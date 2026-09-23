package com.aissistant.app;

import org.json.JSONArray;
import org.json.JSONObject;

/** Transcript prefix retained when a user replaces an earlier message. */
final class ChatBranch {
    private ChatBranch() { }

    /** Returns every turn before the edited user message, or null if the visible message changed. */
    static JSONArray beforeEditedUser(JSONArray bubbles, int editedIndex, String expectedText) {
        if (bubbles == null || editedIndex < 0 || editedIndex >= bubbles.length()) return null;
        JSONObject edited = bubbles.optJSONObject(editedIndex);
        if (edited == null || !"user".equals(edited.optString("role", ""))
                || !same(expectedText, edited.optString("text", ""))) return null;
        JSONArray branch = new JSONArray();
        for (int i = 0; i < editedIndex; i++) {
            JSONObject row = bubbles.optJSONObject(i);
            if (row != null) branch.put(row);
        }
        return branch;
    }

    private static boolean same(String first, String second) {
        return first == null ? second == null || second.isEmpty() : first.equals(second);
    }
}
