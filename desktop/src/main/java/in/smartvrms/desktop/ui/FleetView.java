package in.smartvrms.desktop.ui;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.function.Consumer;

/** Read-only design specimens, kept separate from persisted fleet records. */
public final class FleetView extends JPanel {
    private static final java.util.List<DemoData.Vehicle> EXAMPLES=DemoData.VEHICLES;
    private static final java.util.List<String> NAMES=EXAMPLES.stream().map(DemoData.Vehicle::name).toList();
    private final DefaultListModel<String> model=new DefaultListModel<>();
    private final JList<String> vehicles=new JList<>(model);
    private final JLabel name=Design.label(NAMES.get(0),22,Design.INK);
    private final JTextArea specification=Design.note("");
    private final VehicleDrawing drawing=new VehicleDrawing();
    public FleetView() { this(candidate->{}); }
    public FleetView(Consumer<String> shortlist) {
        super(new BorderLayout(16,16)); setOpaque(false);
        JTextField search=new JTextField(); search.setPreferredSize(new Dimension(160,40));
        JPanel toolbar=new JPanel(new BorderLayout(12,0)); toolbar.setOpaque(false);
        toolbar.add(Design.field("Find a sample vehicle",search)); toolbar.add(Design.label("10 demo vehicles · not saved",12,Design.MUTED),BorderLayout.EAST); add(toolbar,BorderLayout.NORTH);
        for(String value:NAMES) model.addElement(value);
        vehicles.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); vehicles.setFixedCellHeight(100);
        vehicles.setCellRenderer((list,value,index,selected,focus)->{
            JLabel item=new JLabel("<html><span style='font-size:12pt'>"+value+"</span><br><br><span style='color:#637180;font-size:9pt'>Demo only · not a live fleet record</span></html>"); item.setOpaque(true); item.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,selected?3:0,1,0,selected?Design.PRIMARY:Design.BORDER),BorderFactory.createEmptyBorder(14,16,14,16)));
            item.setBackground(selected?new Color(0xEAF0EC):Color.WHITE);
            return item;
        });
        vehicles.getAccessibleContext().setAccessibleName("Example fleet; use arrow keys to select");
        vehicles.addListSelectionListener(e->{if(!e.getValueIsAdjusting()&&vehicles.getSelectedValue()!=null) select(vehicles.getSelectedValue());});
        search.getDocument().addDocumentListener(new DocumentListener(){
            private void filter(){model.clear();for(String value:NAMES) if(value.toLowerCase(java.util.Locale.ROOT).contains(search.getText().toLowerCase(java.util.Locale.ROOT))) model.addElement(value); if(!model.isEmpty()) vehicles.setSelectedIndex(0);else {name.setText("No matching examples");specification.setText("Clear or change the search to browse the sample fleet.");}}
            public void insertUpdate(DocumentEvent e){filter();} public void removeUpdate(DocumentEvent e){filter();} public void changedUpdate(DocumentEvent e){filter();}
        });
        JPanel detail=Design.card("Vehicle profile","Illustrative vehicle · no live fleet data");
        detail.add(drawing); detail.add(Box.createVerticalStrut(20)); detail.add(name); detail.add(Box.createVerticalStrut(8)); detail.add(specification);
        detail.add(Box.createVerticalStrut(24)); detail.add(new JSeparator()); detail.add(Box.createVerticalStrut(20));
        detail.add(Design.label("Availability needs rental dates",15,Design.INK)); detail.add(Box.createVerticalStrut(8));
        detail.add(Design.note("Shortlist this example, enter a rental period, then review your draft. The connected service will check availability before confirmation."));
        detail.add(Box.createVerticalStrut(18)); JButton choose=Design.button("Use in rental draft",true);
        choose.addActionListener(e->{if(vehicles.getSelectedValue()!=null) shortlist.accept(vehicles.getSelectedValue());}); detail.add(choose);
        vehicles.addListSelectionListener(e->choose.setEnabled(vehicles.getSelectedValue()!=null));
        Design.WidthPanel detailWidth=new Design.WidthPanel(new BorderLayout()); detailWidth.add(detail,BorderLayout.NORTH);
        JSplitPane split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,Design.scroll(vehicles),Design.scroll(detailWidth));
        split.setBorder(null); split.setResizeWeight(.4); split.setDividerSize(8); split.setDividerLocation(290);
        split.getLeftComponent().setMinimumSize(new Dimension(235,100)); split.getRightComponent().setMinimumSize(new Dimension(320,100)); add(split);
        vehicles.setSelectedIndex(0);
    }
    private void select(String value) { int i=NAMES.indexOf(value);DemoData.Vehicle vehicle=EXAMPLES.get(i);name.setText(value);specification.setText(vehicle.category()+" · "+vehicle.specification()+"\nSample reference: "+vehicle.demoRegistration()+" (not a valid registration)");drawing.scooter=vehicle.category().equals("SCOOTER");drawing.repaint(); }
    /** In-repository vector illustration, explicitly not a vehicle photograph. */
    private static final class VehicleDrawing extends JComponent {
        boolean scooter;
        VehicleDrawing(){setPreferredSize(new Dimension(240,130));getAccessibleContext();}
        @Override protected void paintComponent(Graphics graphics){
            Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0xF1F4F3));g.fillRoundRect(0,0,getWidth(),getHeight(),8,8);
            double scale=Math.min(.95,(getWidth()-30)/260.0);g.translate((getWidth()-260*scale)/2,16);g.scale(scale,scale);
            g.setColor(new Color(0xBECBC5));
            if(scooter){g.fillRoundRect(63,45,100,22,12,12);g.setStroke(new BasicStroke(8));g.drawLine(160,16,185,76);g.drawLine(140,16,163,16);g.setColor(Design.INK);g.fillRoundRect(60,32,65,10,5,5);}
            else {g.fillRoundRect(15,43,230,40,15,15);g.fillPolygon(new int[]{55,88,163,203},new int[]{45,10,10,45},4);g.setColor(new Color(0xE0E8E5));g.fillPolygon(new int[]{72,94,157,181},new int[]{40,18,18,40},4);g.setColor(Design.PRIMARY);g.fillRoundRect(25,53,210,26,10,10);}
            g.setColor(Design.INK);g.fillOval(48,64,35,35);g.fillOval(177,64,35,35);g.setColor(Color.WHITE);g.fillOval(58,74,15,15);g.fillOval(187,74,15,15);g.dispose();
        }
    }
}
