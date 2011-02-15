/***************************************************************
Copyright � 2011 52�North Initiative for Geospatial Open Source Software GmbH

 Author: Matthias Mueller, TU Dresden
 
 Contact: Andreas Wytzisk, 
 52�North Initiative for Geospatial Open Source SoftwareGmbH, 
 Martin-Luther-King-Weg 24,
 48155 Muenster, Germany, 
 info@52north.org

 This program is free software; you can redistribute it and/or
 modify it under the terms of the GNU General Public License
 version 2 as published by the Free Software Foundation.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; even without the implied WARRANTY OF
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program (see gnu-gpl v2.txt). If not, write to
 the Free Software Foundation, Inc., 59 Temple Place - Suite 330,
 Boston, MA 02111-1307, USA or visit the Free
 Software Foundation�s web page, http://www.fsf.org.

 ***************************************************************/

package org.n52.wps.python;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.opengis.wps.x100.ProcessDescriptionType;

import org.apache.log4j.Logger;
import org.n52.wps.io.data.GenericFileData;
import org.n52.wps.io.data.GenericFileDataConstants;
import org.n52.wps.io.data.IData;
import org.n52.wps.io.data.binding.complex.GenericFileDataBinding;
import org.n52.wps.server.IAlgorithm;
import org.n52.wps.server.feed.movingcode.AlgorithmParameterType;
import org.n52.wps.server.feed.movingcode.CommandLineParameter;
import org.n52.wps.server.feed.movingcode.MovingCodeUtils;
import org.n52.wps.server.feed.movingcode.MovingCodeObject;

public class PythonScriptDelegator implements IAlgorithm{
	private static final String COMMAND = "cmd /c";
	
	private static Logger LOGGER = Logger.getLogger(PythonScriptDelegator.class);
	
	private CommandLineParameter[] scriptParameters;
	
	private MovingCodeObject mco;
	protected List<String> errors;

	public PythonScriptDelegator(MovingCodeObject mco, File workspaceBase) throws IOException{
		this.errors = new ArrayList<String>();
		this.mco = mco.createChild(workspaceBase);
		this.scriptParameters = new CommandLineParameter[mco.getParameters().size()];
	}
	
	
	public Map<String, IData> run(Map<String, List<IData>> inputData) {
		File instanceWorkspace = new File(mco.getInstanceWorkspace().getAbsolutePath());
		String instanceExecutable = instanceWorkspace + File.separator + mco.getAlgorithmURL().getPublicPath();
		
		List<AlgorithmParameterType> params = mco.getParameters();
		
		HashMap<String, String> outputs = new HashMap<String,String>();
		
		for (AlgorithmParameterType currentParam : params){
			String wpsInputID = currentParam.getWpsInputID();
			String wpsOutputID = currentParam.getWpsOutputID();
			String prefixString = currentParam.getPrefixString();
			int positionID = currentParam.getPositionID().intValue();
			
			CommandLineParameter cmdParam = null;
			
			// input parameters
			if(wpsInputID != null){
				if(inputData.containsKey(wpsInputID)){
					//open the IData list and iterate through it
					List<IData> dataItemList = inputData.get(wpsInputID);
					Iterator<IData> it = dataItemList.iterator();
					//create CommanLineParameter Object
					cmdParam = new CommandLineParameter(currentParam.getPrefixString(), currentParam.getSuffixString(), currentParam.getSeparatorString());
					while (it.hasNext()){
						IData currentItem = it.next();
						// load as file and add to CommanLineParameter
						cmdParam.addValue((MovingCodeUtils.loadSingleDataItem(currentItem, instanceWorkspace)));
					}
				}
			} 
			
			// output only parameters !!ONLY SINGLE OUTPUT ITEMS SUPPORTED BY WPS!!
			else if (wpsOutputID != null){
				// create CommanLineParameter Object
				cmdParam = new CommandLineParameter(currentParam.getPrefixString(), currentParam.getSuffixString(), currentParam.getSeparatorString());
				
				// retrieve the default mimeType
				String mimeType = mco.getDefaultMimeType(wpsOutputID);
				
				// prepare output filename
				String extension = GenericFileDataConstants.mimeTypeFileTypeLUT().get(mimeType);
				String fileName = System.currentTimeMillis() + "." + extension;
				fileName = instanceWorkspace.getAbsolutePath() + File.separator + fileName;
				cmdParam.addValue(fileName);
			}
			
			//prepare the output - files only
			if (wpsOutputID != null){
				String fileName = cmdParam.getAsPlainString();
				outputs.put(wpsOutputID, fileName);
			}
			
			// create a new parameter in the Parameter Array
			scriptParameters[positionID] = cmdParam;
		}
		
		// initialize execution
		LOGGER.info("Executing CommandLine Algorithm " + instanceExecutable + " . Parameter array contains " + scriptParameters.length + " parameters.");
		
		// build the execution command
		String command = COMMAND + " " + instanceExecutable;
		for (CommandLineParameter currentParam : scriptParameters){
			command = command + " " + currentParam.getAsCommandString();
		}
		
		// execute
		LOGGER.info("Executing " + command);
		executeScript(command, instanceWorkspace);
		
		//create the output - files only
		HashMap<String, IData> result = new HashMap<String, IData>();
		for (String wpsOutputID : outputs.keySet()){
			// create File object
			File currentFile = new File (outputs.get(wpsOutputID));
			GenericFileData outputFileData;
			try {
				// create the GenericFileData object
				outputFileData = new GenericFileData(currentFile, mco.getDefaultMimeType(wpsOutputID));
				// put result on output map
				result.put(wpsOutputID, new GenericFileDataBinding(outputFileData));
			} catch (FileNotFoundException e) {
				LOGGER.error("Could not read output file: " + outputs.get(wpsOutputID));
				e.printStackTrace();
			} catch (IOException e) {
				LOGGER.error("Could not create output file from: " + outputs.get(wpsOutputID));
				e.printStackTrace();
			}
		}
		
		return result;
	}

	public List<String> getErrors() {
		return errors;
	}

	public ProcessDescriptionType getDescription() {
		return mco.getProcessDescription();
	}

	public String getWellKnownName() {
		return mco.getProcessID();
	}

	public boolean processDescriptionIsValid() {
		return mco.getProcessDescription().validate();
	}

	public Class getInputDataType(String id) {
		return MovingCodeUtils.getInputDataType(mco, id);
	}

	public Class getOutputDataType(String id) {
		return MovingCodeUtils.getOutputDataType(mco, id);
	}
	
	private void executeScript(String command, File workspaceDir) //throws Exception
	{
		try {
			Process p = Runtime.getRuntime().exec(command, null, workspaceDir);
			p.waitFor();
			if (p.exitValue() == 0){
				LOGGER.info("Successfull termination of command:\n" + command);
			}
			else {
				LOGGER.error("Abnormal termination of command:\n" + command);
				LOGGER.error("Errorlevel / Exit Value: " + p.exitValue());
				throw new IOException();
			}
		}
		catch (IOException e) {
			LOGGER.error("Error executing command:\n" + command);
			e.printStackTrace();
			//throw new Exception();
		} catch (InterruptedException e) {
			LOGGER.error("Execution interrupted! Command was:\n" + command);
			e.printStackTrace();
			//throw new Exception();
		}
	}
	
}