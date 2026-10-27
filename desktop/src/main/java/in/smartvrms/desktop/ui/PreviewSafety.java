package in.smartvrms.desktop.ui;

import javax.swing.*;
import java.awt.*;

/** Unconnected prototypes cannot present local table edits as persisted operations. */
public final class PreviewSafety {
    private PreviewSafety() { }
    public static JPanel readOnly(JComponent example) {
        disableActions(example);
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.add(Design.card("Read-only demo workspace",
                "The records below are fictional examples for exploring the workflow. Nothing is saved. Sign in to see your company's records."), BorderLayout.NORTH);
        panel.add(example);
        return panel;
    }
    public static void disableActions(Component component) {
        if (component instanceof JButton button) {
            button.setEnabled(false);
            button.setToolTipText("Unavailable: this example has no connected service.");
        }
        if (component instanceof Container container)
            for (Component child : container.getComponents()) disableActions(child);
    }
}
