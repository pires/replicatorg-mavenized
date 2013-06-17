package replicatorg.plugin.toolpath.slic3r;

import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Vector;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

import net.miginfocom.swing.MigLayout;
import replicatorg.app.Base;
import replicatorg.app.gcode.MutableGCodeSource;
import replicatorg.machine.model.MachineType;
import replicatorg.machine.model.ToolheadAlias;
import replicatorg.model.BuildCode;
import replicatorg.model.GCodeSource;
import replicatorg.plugin.toolpath.slic3r.Slic3rGenerator.Slic3rBooleanPreference;
import replicatorg.plugin.toolpath.slic3r.Slic3rGenerator.Slic3rOption;
import replicatorg.plugin.toolpath.slic3r.Slic3rGenerator.Slic3rPreference;

/**
 * The Slic3r Post Processor does anything that Slic3r doesn't
 * The Slic3rGenerator calls this after it has finished running Slic3r,
 * and this does things like swapping toolhead, cleaning up comments, 
 * adding start/end code, etc.
 * 
 * This also offers, through the PostProcessorPreference class, a gui
 * for selecting some of the things that get run.
 * 
 * @author Ted
 *
 */
public class Slic3rPostProcessor {
	
	private class PostProcessorPreference implements Slic3rPreference {
		private final JPanel panel = new JPanel(new MigLayout("fill, ins 0"));
		private final Slic3rPostProcessor processor;
		
		public PostProcessorPreference(Slic3rPostProcessor spp) {
			processor = spp;
		}
		
		/**
		 * If some PostProcessor setting would affect the displayed preferences, 
		 * then this should be called in its setter
		 */
		public void refreshPreferences() {
			panel.removeAll();
			
			if(multiHead && !dualstruding)
			{
				Vector<String> extruders = new Vector<String>();
				extruders.add(ToolheadAlias.RIGHT.guiName);
				extruders.add(ToolheadAlias.LEFT.guiName);

				String value = Base.preferences.get("replicatorg.slic3r.toolheadOrientation", extruders.firstElement());
				
				final DefaultComboBoxModel model= new DefaultComboBoxModel(extruders);
				
				JComboBox input = new JComboBox(model);
				final JCheckBox toolSwap = new JCheckBox("Use");
				panel.add(toolSwap, "split");
				panel.add(input, "split");
				panel.add(new JLabel("extruder"), "wrap");

				ActionListener toolSelected = new ActionListener(){
					@Override
					public void actionPerformed(ActionEvent arg0) {
						
						Base.preferences.putBoolean("replicatorg.slic3r.setToolheadOrientation", toolSwap.isSelected());
						if(toolSwap.isSelected()) {
							if(model.getSelectedItem().equals(ToolheadAlias.LEFT.guiName)) {
								processor.toolheadTarget = ToolheadAlias.LEFT;
								Base.preferences.put("replicatorg.slic3r.toolheadOrientation", ToolheadAlias.LEFT.guiName);
							}
							else if(model.getSelectedItem().equals(ToolheadAlias.RIGHT.guiName)) {
								processor.toolheadTarget = ToolheadAlias.RIGHT;
								Base.preferences.put("replicatorg.slic3r.toolheadOrientation", ToolheadAlias.RIGHT.guiName);
							}
						}
						else {
							processor.toolheadTarget = null;
							Base.preferences.put("replicatorg.slic3r.toolheadOrientation", "-");
						}
					}
				};
				input.addActionListener(toolSelected);
				toolSwap.addActionListener(toolSelected);

				toolSwap.setSelected(Base.preferences.getBoolean("replicatorg.slic3r.setToolheadOrientation", true));
				model.setSelectedItem(value);

				input.setToolTipText("select which extruder this gcode prints on");
			}
		}
		
		@Override
		public JComponent getUI() {
			return panel;
		}
		@Override
		public List<Slic3rOption> getOptions() {
			return new ArrayList<Slic3rOption>();
		}
		@Override
		public String valueSanityCheck() {
			// TODO Auto-generated method stub
			return null;
		}
		@Override
		public String getName() {
			return "Post-Processor options";
		}
	}
	
	private final Slic3rGenerator generator;
	
	private MutableGCodeSource source;
	
	// options:
	private MutableGCodeSource startCode = null;
	private MutableGCodeSource endCode = null;
	private ToolheadAlias toolheadTarget = null;
	private MachineType machineType = null;
	private boolean dualstruding = false;
	private boolean prependStart = false;
	private boolean appendEnd = false;
	private boolean prependMetaInfo = false;
	private boolean multiHead = false;
	private boolean addProgressUpdates = true;
	private PostProcessorPreference ppp;
	
	public Slic3rPostProcessor(Slic3rGenerator generator)
	{
		this.generator = generator;
		
		// This allows us to display stuff in the configuration dialog,
		//and to send option overrides to Slic3r
		ppp = new PostProcessorPreference(this);
	}
	
	/**
	 * does the post-processing, called by Slic3r Generator
	 * @return
	 */
	protected BuildCode runPostProcessing()
	{
		// Load our code to a source iterator
		source = new MutableGCodeSource(generator.output.file);
		
		if(!dualstruding)
		{
			if(prependStart)
				runPrepend(startCode);
			if(appendEnd)
				runAppend(endCode);

			if( !multiHead )
				toolheadTarget = ToolheadAlias.SINGLE; 
		
			if(toolheadTarget != null)
				runToolheadSwap(toolheadTarget);
		}
		
		// these display the build % on The Replicator
		if(addProgressUpdates)
		{
			source.addSlic3rProgressUpdates();
		}
		
		if(prependMetaInfo)
		{
			MutableGCodeSource metaInfo = new MutableGCodeSource();
			String curDate = getPrettyPrintDate();
			String machineName = (machineType != null ? machineType.getName() : "CNC Machine");
			//metaInfo.add("(** UUID: " + UUID.randomUUID().toString() + " **)");
			metaInfo.add("(** This GCode was generated by ReplicatorG "+Base.VERSION_NAME+" **)");
			//TRICKY: calling a static method on an instance of a class is considered bad practice,
			//				but I'm not sure how to access displayName without it
			metaInfo.add("(*  using "+generator.displayName+"  *)");
			metaInfo.add("(*  for a "+(multiHead?"Dual headed ":"Single headed ")+machineName+"  *)");
			metaInfo.add("(*  on "+ curDate + " *)");
			
			runPrepend(metaInfo);
		}
		
		// scans to cool unused head if required
//		if( multiHead )	
//			source.coolUnusedToolhead();
		
		//Write the modified source back to our file
		source.writeToFile(generator.output.file);
		
		return generator.output;
	}
	
	private String getPrettyPrintDate() {
		DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss (Z)");
		//get current date time with Date()
		Date date = new Date();
		return dateFormat.format(date);
	}
		   
	/**
	 * switches all toolhead specific code to the target toolhead
	 * @param switchTo
	 */
	private void runToolheadSwap(ToolheadAlias switchTo)
	{
		System.out.println("runToolheadSwap");
		source.changeToolhead(switchTo);
	}
	
	/**
	 * prepends code to the file
	 * @param newCode
	 */
	private void runPrepend(GCodeSource newCode)
	{
		if(newCode != null)
			source.add(0, newCode);
	}
	
	/**
	 * appends code to the file
	 * @param newCode
	 */
	private void runAppend(GCodeSource newCode)
	{
		if(newCode != null)
			source.add(newCode);
	}
	
	/**
	 * indicates that the code will be used as part of a dualstrusion print
	 * implies a variety of things, supplies a special UI for the ConfigurationDialog
	 */
	public void enableDualstrusion()
	{
		dualstruding = true;
		
		List<Slic3rPreference> prefs = generator.getPreferences();
		
		// This allows us to display stuff in the configuration dialog,
		//and to send option overrides to slic3r
		prefs.add(0, new Slic3rPreference(){
			Slic3rBooleanPreference outlineActive;
			Slic3rBooleanPreference coolActive;
			//Static block
			{
				outlineActive = new Slic3rBooleanPreference("Outline Active", 
						"slic3r.dualstrusion.outlineActive", false, "<html>Having Outline active for any layer" +
								" but the first layer<br/>for the first toolhead can damage dualstrusion prints.</html>");
				outlineActive.addNegateableOption(new Slic3rOption("--skirts", "1"));
				
				//NOT ACTIVE ON SLIC3R YET
				/*
				coolActive = new Slic3rBooleanPreference("Cool Active", 
						"slic3r.dualstrusion.coolActive", false, "<html>Cool makes the tool move slowly on very small " +
						"layers,<br/> with dualstrusion, those layers are usually supported by the other half of the print.</html>");
				coolActive.addNegateableOption(new Slic3rOption("cool.csv", "Activate Cool", "True"));
			*/
				}
			@Override
			public JComponent getUI() {
				JPanel panel = new JPanel();
				panel.setBorder(BorderFactory.createLineBorder(Color.BLACK));
				panel.setLayout(new MigLayout("fillx, filly"));
				
				panel.add(new JLabel("Dualstruding..."), "growx, wrap");
				panel.add(outlineActive.getUI(), "growx, wrap");
				//panel.add(coolActive.getUI(), "growx, wrap");
				
				return panel;
			}
			@Override
			public List<Slic3rOption> getOptions() {
				List<Slic3rOption> result = new ArrayList<Slic3rOption>();
				result.add(new Slic3rOption("--start-gcode",""));
				result.add(new Slic3rOption("--end-gcode",""));
				result.addAll(outlineActive.getOptions());
				//result.addAll(coolActive.getOptions());
				return result;
			}
			@Override
			public String valueSanityCheck() {
				// TODO Auto-generated method stub
				return null;
			}
			@Override
			public String getName() {
				return "Dualstrusion options";
			}
		});
	}
	/**
	 * sets the toolhead the code is being generated for
	 * @param tool
	 */
	public void setToolheadTarget(ToolheadAlias tool)
	{
		toolheadTarget = tool;
	}
	
	/**
	 * sets the type of machine the code is being generated for
	 * @param type
	 */
	public void setMachineType(MachineType type)
	{
		machineType = type;
	}

	/**
	 * Sets the code to add to the beginning of a file
	 * @param source
	 */
	public void setStartCode(GCodeSource source)
	{
		if(source == null)
			startCode = null;
		else if(source instanceof MutableGCodeSource)
			startCode = (MutableGCodeSource)source;
		else
			startCode = new MutableGCodeSource(source);
	}
	/**
	 * Sets the code to add to the end of a file
	 * @param source
	 */
	public void setEndCode(GCodeSource source)
	{
		if(source == null)
			endCode = null;
		if(source instanceof MutableGCodeSource)
			endCode = (MutableGCodeSource)source;
		else
			endCode = new MutableGCodeSource(source);
	}

	/**
	 * toggles the addition of start code to the beginning of a file
	 * setStartCode must be called to supply the code to add
	 * @param doAppend
	 */
	public void setPrependStart(boolean doPrepend)
	{
		prependStart = doPrepend;
	}
	/**
	 * toggles the addition of end code to the end of a file
	 * setEndCode must be called to supply the code to add
	 * @param doAppend
	 */
	public void setAppendEnd(boolean doAppend)
	{
		appendEnd = doAppend;
	}

	/**
	 * toggles the addition of timestamps & other information about the creation process
	 * @param doPrepend
	 */
	public void setPrependMetaInfo(boolean doPrepend)
	{
		prependMetaInfo = doPrepend;
	}
	/**
	 * specifies whether the machine has one or more heads
	 * @param isMulti
	 */
	public void setMultiHead(boolean isMulti)
	{
		multiHead = isMulti;
		ppp.refreshPreferences();
	}
	/**
	 * toggles the addition of build % messages, displayable on The Replicator
	 * @param doAdd
	 */
	public void setAddProgressUpdates(boolean doAdd)
	{
		addProgressUpdates = doAdd;
	}
	
	/**
	 * getter for the PostProcessorPreference, used to display post processing steps in the 
	 * Slic3r ConfigurationDialog.
	 * @return
	 */
	public PostProcessorPreference getPreference()
	{
		return ppp;
	}
}
