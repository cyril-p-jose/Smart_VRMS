import in.smartvrms.desktop.DesktopMain;
import in.smartvrms.desktop.ui.Design;
import in.smartvrms.desktop.ui.WorkspaceView;
import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.nio.file.*;
import javax.imageio.ImageIO;

/** Real local window smoke, not full human device acceptance. */
public final class NativeWindowSmoke {
    public static void main(String[] args) throws Exception {
        if(GraphicsEnvironment.isHeadless()) throw new IllegalStateException("A desktop session is required.");
        JFrame[] frame=new JFrame[1]; WorkspaceView[] view=new WorkspaceView[1];
        boolean[] keyboardPassed=new boolean[1];
        try {
            SwingUtilities.invokeAndWait(()->{
                Design.install(); frame[0]=new JFrame("Smart VRMS • Native window verification");
                view[0]=new WorkspaceView(()->{}); frame[0].setContentPane(view[0]);
                frame[0].setMinimumSize(new Dimension(1024,720)); frame[0].setSize(1280,820); frame[0].setLocationRelativeTo(null);
                frame[0].setVisible(true); view[0].showPage("New booking"); view[0].booking.customer.requestFocusInWindow();
            });
            Robot robot=new Robot(); robot.setAutoDelay(100); robot.waitForIdle(); robot.delay(300);
            SwingUtilities.invokeAndWait(()->{
                if(!view[0].booking.customer.isFocusOwner()) throw new AssertionError("Initial field focus");
                Component next=frame[0].getFocusTraversalPolicy().getComponentAfter(frame[0],view[0].booking.customer);
                System.out.println("Focus policy next is phone: "+(next==view[0].booking.phone));
            });
            robot.keyPress(KeyEvent.VK_TAB); robot.keyRelease(KeyEvent.VK_TAB); robot.waitForIdle(); robot.delay(150);
            SwingUtilities.invokeAndWait(()->{
                keyboardPassed[0]=view[0].booking.phone.isFocusOwner();
                System.out.println(keyboardPassed[0]?"PASS: OS Tab traversal":"FAIL: OS-injected Tab did not move focus to phone; manual keyboard verification required.");
                frame[0].setSize(1024,720);
            });
            robot.waitForIdle(); robot.delay(200);
            Rectangle[] bounds=new Rectangle[1];
            SwingUtilities.invokeAndWait(()->{
                Point action=view[0].booking.check.getLocationOnScreen(); Rectangle window=frame[0].getBounds();
                if(!window.contains(action)) throw new AssertionError("Draft action outside native window");
                bounds[0]=window;
            });
            ImageIO.write(robot.createScreenCapture(bounds[0]),"png",Path.of("verification/generated/native-window.png").toFile());
            SwingUtilities.invokeAndWait(()->frame[0].setExtendedState(JFrame.MAXIMIZED_BOTH)); robot.waitForIdle();
            System.out.println("PASS: native frame launch, resize, field focus, action location, maximize request; scale="+GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getDefaultTransform().getScaleX());
        } finally { SwingUtilities.invokeAndWait(()->{if(frame[0]!=null) frame[0].dispose();}); }
        SwingUtilities.invokeAndWait(()->{for(Window w:Window.getWindows()) if(w.isDisplayable()) throw new AssertionError("Window not disposed");});
        System.out.println("PASS: all verification windows disposed.");
        if(!keyboardPassed[0]) throw new AssertionError("Native keyboard check unresolved; do not mark device acceptance passed.");
    }
}
