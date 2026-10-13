/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.gametest;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

final class ChatEvidence {
    private ChatEvidence() {}
    static String text(Component component) {
        var result = new StringBuilder(component.getString());
        var hover = component.getStyle().getHoverEvent();
        if (hover != null && hover.getValue(HoverEvent.Action.SHOW_TEXT) != null)
            result.append('\n').append(hover.getValue(HoverEvent.Action.SHOW_TEXT).getString());
        for (var child : component.getSiblings()) result.append('\n').append(text(child));
        return result.toString();
    }
    static String eventId(Component component) {
        var hover = component.getStyle().getHoverEvent();
        if (hover != null) {
            var value = hover.getValue(HoverEvent.Action.SHOW_TEXT);
            if (value != null && value.getString().contains("Event ID: "))
                return value.getString().split("Event ID: ", 2)[1];
        }
        for (var child : component.getSiblings()) {
            String id = eventId(child);
            if (id != null) return id;
        }
        return null;
    }
}
