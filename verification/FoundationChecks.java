import in.smartvrms.application.booking.DraftValidator;
import in.smartvrms.contracts.BookingDraft;
import in.smartvrms.desktop.DesktopMain;
import in.smartvrms.desktop.integration.UnavailableAuthentication;
import in.smartvrms.desktop.infrastructure.DesktopConfig;
import in.smartvrms.desktop.ui.*;
import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.LocalDateTime;

/** Dependency-free Stage 1 checks. No provider or database behavior is simulated. */
public final class FoundationChecks {
    private static int checks;
    private static void require(boolean result,String message) { if(!result) throw new AssertionError(message); checks++; }
    public static void main(String[] args) throws Exception {
        Path output=Path.of("verification/generated"); Files.createDirectories(output);
        var validator=new DraftValidator(); LocalDateTime start=LocalDateTime.of(2026,10,25,9,0);
        require(validator.validate(new BookingDraft("","",null,null,false)).size()==3,"required fields");
        require(!validator.validate(new BookingDraft("Example","9876543210",start,start,false)).isEmpty(),"zero duration rejected");
        require(!validator.validate(new BookingDraft("Example","9876543210",start,start.minusHours(1),false)).isEmpty(),"negative duration rejected");
        require(validator.validate(new BookingDraft("അനു Example","+91 98765 43210",start,start.plusDays(1),true)).isEmpty(),"unicode input");
        require(!new UnavailableAuthentication().isConfigured(),"authentication fails closed");
        require(DesktopConfig.fromEndpoint(null).apiEndpoint().isEmpty(),"unconfigured endpoint allowed for preview");
        require(DesktopConfig.fromEndpoint("https://example.invalid/api").apiEndpoint().isPresent(),"public HTTPS config");
        for(String unsafe:new String[]{"http://example.invalid","https://user:secret@example.invalid","https://example.invalid/?token=secret","invalid"}) {
            try { DesktopConfig.fromEndpoint(unsafe); throw new AssertionError("Unsafe endpoint accepted"); }
            catch(IllegalArgumentException expected) { require(!expected.getMessage().contains("secret"),"configuration error redaction"); }
        }
        try { DesktopMain.createContent(); throw new AssertionError("EDT guard"); } catch(IllegalStateException expected) { checks++; }
        SwingUtilities.invokeAndWait(()->{
            try {
                Design.install();
                JPanel root=DesktopMain.createContent();
                for(int[] size:new int[][]{{1024,720},{1280,820},{1600,1000}}) {
                    render(root,size[0],size[1],output.resolve("signin-"+size[0]+".png"));
                    require(findButton(root,"Sign in — not connected")!=null&&!findButton(root,"Sign in — not connected").isEnabled(),"sign-in disabled");
                }
                WorkspaceView workspace=new WorkspaceView(()->{});
                for(String page:new String[]{"Overview","Vehicles","New booking","Personnel"}) {
                    workspace.showPage(page);
                    for(int[] size:new int[][]{{1024,720},{1280,820},{1600,1000}}) {
                        render(workspace,size[0],size[1],output.resolve(page.replace(' ','-').toLowerCase()+"-"+size[0]+".png"));
                        if(page.equals("New booking")) {
                            require(noHorizontalScroll(workspace.booking),"booking fits viewport at "+size[0]);
                            Point position=SwingUtilities.convertPoint(workspace.booking.check,0,0,workspace);
                            require(position.x>=0&&position.y>=0&&position.y+workspace.booking.check.getHeight()<=size[1],"draft action visible at "+size[0]);
                        }
                    }
                }
                workspace.showPage("New booking"); BookingView booking=workspace.booking;
                workspace.showPage("Vehicles");
                findButton(workspace,"Use in rental draft").doClick();
                require("City hatchback".equals(booking.candidate()),"fleet shortlist carried into booking");
                booking.check.doClick(); require(booking.feedback.getText().contains("Enter the customer's name"),"required feedback");
                render(workspace,1024,720,output.resolve("booking-errors.png"));
                booking.customer.setText("Example person with an unusually long name for layout inspection"); booking.phone.setText("9876543210");
                booking.pickup.setText("31/02/2026 09:00"); booking.expectedReturn.setText("01/03/2026 09:00");
                booking.check.doClick(); require(booking.feedback.getText().contains("Enter pickup"),"strict calendar parsing");
                booking.pickup.setText("25/10/2026 09:00"); booking.expectedReturn.setText("26/10/2026 09:00");
                booking.check.doClick(); require(booking.feedback.getText().contains("Nothing has been saved or booked"),"valid draft no fake success");
                require(booking.isReviewing(),"valid draft advances to review");
                findButton(booking,"Edit details").doClick();
                require(!booking.isReviewing()&&booking.customer.getText().startsWith("Example person")&&"City hatchback".equals(booking.candidate()),"edit preserves input and shortlist");
                booking.check.doClick();
                require(!findButton(booking,"Confirm booking — unavailable").isEnabled(),"confirmation disabled");
                render(workspace,1024,720,output.resolve("booking-long-input.png"));
                require(booking.check.isShowing()==false,"offscreen render is not a device test");
                booking.clear.doClick(); require(booking.customer.getText().isEmpty()&&!booking.driver.isSelected()&&booking.candidate()==null&&!booking.isReviewing(),"clear local draft and candidate");
            } catch(Exception ex) { throw new RuntimeException(ex); }
        });
        System.out.println("PASS: "+checks+" assertions; 17 Swing renders. Provider and device acceptance not tested.");
    }
    private static AbstractButton findButton(Container parent,String text) {
        for(Component child:parent.getComponents()) {
            if(child instanceof AbstractButton b&&b.getText().equals(text)) return b;
            if(child instanceof Container c) { var found=findButton(c,text); if(found!=null) return found; }
        } return null;
    }
    private static boolean noHorizontalScroll(Container parent) {
        for(Component child:parent.getComponents()) {
            if(child instanceof JScrollPane pane&&pane.getHorizontalScrollBar().isVisible()) return false;
            if(child instanceof Container inner&&!noHorizontalScroll(inner)) return false;
        } return true;
    }
    private static void layout(Container c) { c.doLayout(); for(Component child:c.getComponents()) if(child instanceof Container inner) layout(inner); }
    private static void render(JComponent component,int width,int height,Path file) throws Exception {
        component.setSize(width,height); for(int i=0;i<4;i++) layout(component);
        verifyNoOverlap(component);
        BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB); Graphics2D graphics=image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        component.printAll(graphics); graphics.dispose(); ImageIO.write(image,"png",file.toFile());
    }
    private static void verifyNoOverlap(Container parent) {
        if(parent instanceof JList||parent instanceof CellRendererPane) return;
        if(parent instanceof JViewport viewport) { if(viewport.getView() instanceof Container view) verifyNoOverlap(view); return; }
        Component[] children=parent.getComponents();
        for(int i=0;i<children.length;i++) {
            Component a=children[i]; if(!a.isVisible()||a.getWidth()==0||a.getHeight()==0) continue;
            if(a instanceof JLabel label && !label.getText().isEmpty() && label.getIcon()==null && label.getWidth()<label.getPreferredSize().width) {
                throw new AssertionError("Clipped label: "+label.getText());
            }
            for(int j=i+1;j<children.length;j++) {
                Component b=children[j]; if(!b.isVisible()) continue;
                if(a.getBounds().intersects(b.getBounds())) throw new AssertionError("Overlapping siblings: "+a.getClass().getSimpleName()+" / "+b.getClass().getSimpleName());
            }
            if(a instanceof Container nested) verifyNoOverlap(nested);
        }
    }
}
