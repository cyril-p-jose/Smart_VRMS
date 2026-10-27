package in.smartvrms.desktop.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicButtonUI;
import java.awt.*;

/** Natural-height rows inside scrolling pages prevent compressed and overlapping content. */
public final class Design {
    public static final Color BACKGROUND=new Color(0xF4F5F7), SURFACE=Color.WHITE;
    public static final Color INK=new Color(0x18232D), MUTED=new Color(0x637180);
    public static final Color PRIMARY=new Color(0x245B4D), BORDER=new Color(0xDDE2E6);
    public static final Color CAUTION=new Color(0x775B28), NAV=new Color(0x192831);
    public static final int GAP=16, CONTROL_HEIGHT=40;
    private Design() { }
    public static void install() {
        try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); }
        catch(Exception ex) { java.util.logging.Logger.getLogger(Design.class.getName()).warning("Default Swing appearance retained."); }
        Font font=new Font(Font.DIALOG,Font.PLAIN,14);
        for(Object key:UIManager.getDefaults().keySet().toArray()) if(key.toString().endsWith(".font")) UIManager.put(key,font);
        UIManager.put("Panel.background",BACKGROUND); UIManager.put("Label.foreground",INK);
        UIManager.put("TextField.border",BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0xB7C2CB)),new EmptyBorder(9,10,9,10)));
        UIManager.put("PasswordField.border",UIManager.get("TextField.border"));
        UIManager.put("ScrollBar.width",12); UIManager.put("ScrollBar.thumb",new Color(0xBAC4CD));
        UIManager.put("ScrollBar.track",BACKGROUND); UIManager.put("Button.disabledText",MUTED);
        UIManager.put("Separator.foreground",BORDER); UIManager.put("Separator.background",BORDER);
    }
    public static JLabel label(String text,int size,Color color) {
        JLabel label=new JLabel(text); label.putClientProperty("html.disable",Boolean.TRUE); label.setFont(new Font(Font.DIALOG,Font.PLAIN,size)); label.setForeground(color); return label;
    }
    public static JPanel vertical() { return new Stack(); }
    private static final class Stack extends JPanel {
        private int row;
        Stack() { super(new GridBagLayout()); setOpaque(false); }
        @Override protected void addImpl(Component c,Object constraints,int index) {
            GridBagConstraints g=new GridBagConstraints(); g.gridx=0; g.gridy=row++; g.weightx=1;
            g.fill=GridBagConstraints.HORIZONTAL; g.anchor=GridBagConstraints.NORTHWEST; super.addImpl(c,g,index);
        }
    }
    public static JPanel card(String title,String subtitle) {
        JPanel panel=vertical(); panel.setOpaque(true); panel.setBackground(SURFACE);
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER),new EmptyBorder(20,20,20,20)));
        panel.add(label(title,18,INK)); panel.add(Box.createVerticalStrut(8));
        if(!subtitle.isBlank()) { panel.add(note(subtitle)); panel.add(Box.createVerticalStrut(12)); } return panel;
    }
    public static JPanel top(Component content) {
        JPanel panel=new JPanel(new BorderLayout()); panel.setOpaque(false); panel.add(content,BorderLayout.NORTH); return panel;
    }
    public static JPanel row(Component... children) {
        JPanel row=new JPanel(new FlowLayout(FlowLayout.LEADING,8,0)); row.setOpaque(false);
        for(Component child:children) row.add(child); return row;
    }
    public static JButton button(String text,boolean primary) {
        JButton button=new JButton(text); styleButton(button,primary); return button;
    }
    public static void styleButton(AbstractButton button,boolean primary) {
        button.setUI(new BasicButtonUI()); button.setFont(new Font(Font.DIALOG,Font.PLAIN,13));
        button.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER),new EmptyBorder(10,14,10,14)));
        button.setRolloverEnabled(true);
        Runnable update=()->{
            boolean active=primary||button.isSelected();
            button.setBackground(!button.isEnabled()?new Color(0xEDF0F2):active?PRIMARY:button.getModel().isRollover()?new Color(0xEDF3F0):SURFACE);
            button.setForeground(!button.isEnabled()?MUTED:active?Color.WHITE:INK);
        };
        button.getModel().addChangeListener(e->update.run()); update.run();
    }
    public static JPanel page(String title,String description,JComponent body) {
        JPanel page=new JPanel(new BorderLayout(16,16)); page.setBorder(new EmptyBorder(22,24,22,24));
        JPanel header=vertical(); header.add(label(title,27,INK)); header.add(Box.createVerticalStrut(4)); header.add(note(description));
        page.add(header,BorderLayout.NORTH); page.add(body); return page;
    }
    public static JScrollPane scroll(JComponent content) {
        JScrollPane pane=new JScrollPane(content); pane.setBorder(null); pane.getViewport().setBackground(BACKGROUND);
        pane.getVerticalScrollBar().setUnitIncrement(24); return pane;
    }
    public static JTextArea note(String text) { return new WrappedText(text); }
    private static final class WrappedText extends JTextArea {
        WrappedText(String text) {
            super(text); setEditable(false); setFocusable(false); setLineWrap(true); setWrapStyleWord(true);
            setOpaque(false); setForeground(MUTED); setFont(new Font(Font.DIALOG,Font.PLAIN,13)); setBorder(null);
        }
        @Override public Dimension getPreferredSize() {
            int width=getWidth()>0?getWidth():320; FontMetrics fm=getFontMetrics(getFont()); int lines=0;
            for(String paragraph:getText().split("\n",-1)) {
                int used=0; lines++;
                for(String word:paragraph.split(" ")) { int length=fm.stringWidth(word+" "); if(used>0&&used+length>width) {lines++;used=0;} used+=length; }
            }
            return new Dimension(100,Math.max(getRows(),lines)*fm.getHeight()+4);
        }
        @Override public Dimension getMinimumSize() { return new Dimension(20,getPreferredSize().height); }
    }
    public static final class WidthPanel extends JPanel implements Scrollable {
        public WidthPanel(LayoutManager layout) { super(layout); setOpaque(false); }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r,int o,int d) { return 24; }
        @Override public int getScrollableBlockIncrement(Rectangle r,int o,int d) { return Math.max(24,r.height-24); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
    public static JPanel field(String title,JTextField input) {
        JPanel field=new JPanel(new BorderLayout(0,7)); field.setOpaque(false);
        JLabel label=label(title,13,INK); label.setLabelFor(input); field.add(label,BorderLayout.NORTH);
        input.setPreferredSize(new Dimension(120,CONTROL_HEIGHT)); field.add(input); return field;
    }
    public static JPanel fields(JPanel first,JPanel second) {
        JPanel group=new JPanel(new GridLayout(2,1,16,14)) {
            private void columns() { ((GridLayout)getLayout()).setRows(getWidth()>=600?1:2); }
            @Override public void doLayout() { columns(); super.doLayout(); }
            @Override public Dimension getPreferredSize() { columns(); return super.getPreferredSize(); }
        };
        group.setOpaque(false);group.add(first);group.add(second);return group;
    }
}
