package in.smartvrms.verification;

import in.smartvrms.application.port.FleetPort;
import in.smartvrms.contracts.*;
import in.smartvrms.desktop.infrastructure.DesktopConfig;
import in.smartvrms.desktop.integration.*;
import in.smartvrms.desktop.ui.*;
import in.smartvrms.server.api.*;
import in.smartvrms.server.integration.*;
import in.smartvrms.server.security.*;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;

/** Real server routes + real desktop adapter/view, with fixture Auth/repository only. */
public final class LiveFleetProtocolChecks {
    static int checks;
    static void check(boolean condition){if(!condition)throw new AssertionError();checks++;}
    static int tableRows(Component component){if(component instanceof JTable table)return table.getRowCount();if(component instanceof Container box)for(Component child:box.getComponents()){int rows=tableRows(child);if(rows>=0)return rows;}return -1;}
    static void layout(Container box){box.doLayout();for(Component child:box.getComponents())if(child instanceof Container container)layout(container);}
    public static void main(String[] args)throws Exception{
        UUID user=UUID.randomUUID(),company=UUID.randomUUID(),category=UUID.randomUUID();
        List<VehicleCategoryRecord> categories=new ArrayList<>();List<VehicleRecord> vehicles=new ArrayList<>();
        FleetPort fixture=(FleetPort)Proxy.newProxyInstance(FleetPort.class.getClassLoader(),new Class<?>[]{FleetPort.class},(p,m,a)->{
            switch(m.getName()){
                case "listCategories":return List.copyOf(categories);
                case "createCategory":var c=new VehicleCategoryRecord(category,(String)a[1],(String)a[2]);categories.add(c);return c;
                case "listVehicles":return List.copyOf(vehicles);
                case "registerVehicle":var v=new VehicleRecord(UUID.randomUUID(),company,(UUID)a[1],"SUV",(String)a[2],(String)a[3],(String)a[4],(Integer)a[5],(Long)a[6],(Long)a[7],"AVAILABLE",null,1);vehicles.add(v);return v;
                default:throw new UnsupportedOperationException();
            }
        });
        VrmsHttpServer.Authorizer authorizer=(token,cap)->{
            if(!Set.of("admin","employee").contains(token))throw new SecurityException("fixture revoked");
            if(token.equals("employee")&&cap.administratorOnly())throw new SecurityException("fixture forbidden");
            return new StaffAuthorizer.Staff(user,company,token.equals("admin")?"ADMINISTRATOR":"EMPLOYEE");
        };
        var auth=new SupabaseAuth(new SupabaseConfig("https://abcdefghijklmnopqrst.supabase.co","sb_publishable_fixture0123456789"));
        var server=new VrmsHttpServer(new HttpServerConfig(0),auth,authorizer,null,null,null,null,null,fixture,null,null);server.start();
        LiveFleetView[] view={null};
        try{
            var config=DesktopConfig.fromEndpoint(server.baseUri().toString());
            DesktopSession session=new DesktopSession(new UnavailableAuthentication());
            var staff=new StaffSession(user,company,"ADMINISTRATOR","",Set.of());
            session.accept(new SignInResult(staff,new SessionTokens("admin","fixture",3600)));
            FleetHttpClient client=new FleetHttpClient(config,session);
            check(client.categories().isEmpty());client.createCategory("SUV","SUV");check(client.categories().get(0).id().equals(category));
            client.registerVehicle(category,"KL07TEST","Test","Vehicle",5,240000,10000);
            check(client.vehicles().size()==1);check(client.vehicles().get(0).manufacturer().equals("Test"));
            session.accept(new SignInResult(staff,new SessionTokens("employee","fixture",3600)));
            try{client.createCategory("BIKE","Bike");throw new AssertionError();}catch(SecurityException expected){check(categories.size()==1);}
            session.accept(new SignInResult(staff,new SessionTokens("admin","fixture",3600)));
            SwingUtilities.invokeAndWait(()->{Design.install();view[0]=new LiveFleetView(client,staff);});
            AtomicInteger count=new AtomicInteger();
            for(int attempt=0;attempt<100;attempt++){SwingUtilities.invokeAndWait(()->count.set(tableRows(view[0])));if(count.get()==1)break;Thread.sleep(20);}
            check(count.get()==1);
            SwingUtilities.invokeAndWait(()->{
                view[0].setSize(1280,720);layout(view[0]);BufferedImage image=new BufferedImage(1280,720,BufferedImage.TYPE_INT_RGB);Graphics2D graphics=image.createGraphics();graphics.setColor(Design.BACKGROUND);graphics.fillRect(0,0,1280,720);view[0].printAll(graphics);graphics.dispose();
                try{Files.createDirectories(Path.of("verification/generated"));ImageIO.write(image,"png",Path.of("verification/generated/live-fleet-1280.png").toFile());}catch(Exception ex){throw new RuntimeException(ex);}
                view[0].close();check(tableRows(view[0])==0);
            });
        }finally{if(view[0]!=null)SwingUtilities.invokeAndWait(view[0]::close);server.stop(0);}
        System.out.println("PASS: "+checks+" live-fleet desktop/API protocol and UI checks. Repository/Auth are fixtures; no live business write claimed.");
    }
}
