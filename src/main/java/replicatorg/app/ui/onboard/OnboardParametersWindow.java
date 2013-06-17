package replicatorg.app.ui.onboard;

import java.awt.Dimension;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JTabbedPane;

import net.miginfocom.swing.MigLayout;
import replicatorg.app.Base;
import replicatorg.app.ui.MainWindow;
import replicatorg.drivers.Driver;
import replicatorg.drivers.OnboardParameters;
import replicatorg.machine.model.ToolModel;


public class OnboardParametersWindow extends JFrame {
	
	private final JTabbedPane paramsTabs;
	private final JButton cancelButton;
	private MachineOnboardParameters onboardParamsTab;
	private boolean disconnectOnExit = false;

	private MainWindow mainwin;

	/// shoot me. I'm passing the main window into this box. I am a bad person - Far F-BOMB
	public OnboardParametersWindow(OnboardParameters targetParams, Driver driver, MainWindow  mainwin)
	{	
		super("Update Machine Options");
		this.mainwin = mainwin;
	
		Image icon = Base.getImage("images/icon.gif", this);
		setIconImage(icon);
		
		setLayout(new MigLayout());
		
		paramsTabs = new JTabbedPane();
		add(paramsTabs, "span 2, wrap");

		onboardParamsTab = new MachineOnboardParameters(targetParams, driver, (JFrame)this);
		paramsTabs.addTab("Motherboard", onboardParamsTab);
		
		String machineType = targetParams.getMachineType();

    // we're removing the toolhead panel for the replicator because it doesn't work and it works in makerware.
    // if you want to use it, fix it and we'll put in a patch
		//if(!(machineType.equals("The Replicator") || machineType.equals("Replicator 2") ||
    //     machineType.equals("MightyBoard") || machineType.equals("MightyBoard(unverified)"))){
      List<ToolModel> tools = driver.getMachine().getTools();
      
      for(ToolModel t : tools)
      {
        paramsTabs.addTab("Extruder " + t.getIndex(), new ExtruderOnboardParameters(targetParams, t,(JFrame)this));
      }
   // }		
		/*String machineType = targetParams.getMachineType();
		if((machineType.equals("MightyBoard") || 
			machineType.equals("The Replicator") || 
			machineType.equals("MightyBoard(unverified)")))
		{
			paramsTabs.addTab("Bot Settings", new BotParameters());
		}*/

		JLabel verifyString = new JLabel("Warning: Machine Type is not verifiable.");
		verifyString.setToolTipText("this machine has no way to verify the EEPORM is a valid layout");
		if(targetParams.canVerifyMachine())
		{
			verifyString = new JLabel("Error: Machine Type "+ targetParams.getMachineType() +" is of unverifed type.");
			verifyString.setToolTipText("this machine can verify, but failed verification. ");

			if (targetParams.verifyMachineId()){
				verifyString = new JLabel("Awesome: You have a verified " + targetParams.getMachineType());
				verifyString.setToolTipText("Everything is great! We know this machine is the right one. ");
			}
			
		}
		add(verifyString);
		
		cancelButton = new JButton("Cancel");
		cancelButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent arg0) {
				OnboardParametersWindow.this.dispose();
			}
		});
		add(cancelButton, "align right");
		
		pack();
		Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
		setLocation((screen.width - getWidth()) / 2, (screen.height - getHeight()) / 2);
	}
	
	@Override
	public void dispose()
	{
		this.disconnectOnExit = onboardParamsTab.disconnectOnExit();	
		boolean leavePreheatRunning = onboardParamsTab.leavePreheatRunning();
		if(mainwin != null && this.disconnectOnExit){
			//REPLICATOR: leave pre-heat, we expect users to reconnect;
			//ToM, Cupcake: keep behavior unchanged, do not start pre-heat
			mainwin.handleDisconnect(leavePreheatRunning, /*dispose machine model*/true); 
		}
		super.dispose();
	}

}
