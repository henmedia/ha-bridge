package com.bwssystems.HABridge.plugins.NestBridge;

import java.util.ArrayList;
import java.util.ListIterator;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bwssystems.HABridge.BridgeSettingsDescriptor;
import com.bwssystems.HABridge.DeviceMapTypes;
import com.bwssystems.HABridge.api.CallItem;
import com.bwssystems.HABridge.api.hue.DeviceState;
import com.bwssystems.HABridge.api.hue.StateChangeBody;
import com.bwssystems.HABridge.dao.DeviceDescriptor;
import com.bwssystems.HABridge.hue.BrightnessDecode;
import com.bwssystems.HABridge.hue.MultiCommandUtil;
import com.bwssystems.nest.controller.Home;
import com.bwssystems.nest.controller.Nest;
import com.bwssystems.nest.controller.NestSession;
import com.bwssystems.nest.controller.Thermostat;
import com.bwssystems.nest.protocol.error.LoginException;
import com.bwssystems.nest.protocol.status.WhereDetail;
import com.bwssystems.nest.protocol.status.WhereItem;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class NestHome implements com.bwssystems.HABridge.Home {
    private static final Logger log = LoggerFactory.getLogger(NestHome.class);
    private NestSession theSession;
    private Nest theNest;
    private ArrayList<NestItem> nestItems;
	private Gson aGsonHandler;
	private Boolean isFarenheit;
    private Boolean validNest;
    
	public NestHome(BridgeSettingsDescriptor bridgeSettings) {
		super();
		createHome(bridgeSettings);
	}
	
	@Override
	public Object getItems(String type) {
		if(!validNest)
			return null;
		
		if(nestItems == null) {
			nestItems = new ArrayList<NestItem>();
			Set<String> homeNames = theNest.getHomeNames();
			Home aHome = null;
			NestItem anItem = null;
			for(String name : homeNames) {
				aHome = theNest.getHome(name);
				anItem = new NestItem();
				anItem.setId(name);
				anItem.setName(aHome.getDetail().getName());
				anItem.setType("Home");
				anItem.setLocation(aHome.getDetail().getLocation());
				nestItems.add(anItem);
			}
			Thermostat thermo = null;
			Set<String> thermoNames = theNest.getThermostatNames();
			for(String name : thermoNames) {
				thermo = theNest.getThermostat(name);
				anItem = new NestItem();
				anItem.setId(name);
				anItem.setType("Thermostat");
				String where = null;
				String homeName= null;
				Boolean found = false;
				for(String aHomeName : homeNames) {
					WhereDetail aDetail = theNest.getWhere(aHomeName);
					ListIterator<WhereItem> anIterator = aDetail.getWheres().listIterator();
					while(anIterator.hasNext()) {
						WhereItem aWhereItem = (WhereItem) anIterator.next();
						if(aWhereItem.getWhereId().equals(thermo.getDeviceDetail().getWhereId())) {
							where = aWhereItem.getName();
							homeName = theNest.getHome(aHomeName).getDetail().getName();
							found = true;
							break;
						}
					}
					if(found)
						break;
				}
				anItem.setName(where + "(" + name.substring(name.length() - 4) + ")");
				anItem.setLocation(where + " - " + homeName);
				nestItems.add(anItem);
			}
		}
		
		return nestItems;
	}
	
	@Override
	public void closeHome() {
		if(theSession != null) {
			theNest.endNestSession();
			theNest = null;
			theSession = null;
			nestItems = null;
		}
	}

	@Override
	public String deviceHandler(CallItem anItem, MultiCommandUtil aMultiUtil, String lightId, int iterationCount,
			DeviceState state, StateChangeBody theStateChanges, boolean stateHasBri, boolean stateHasBriInc, DeviceDescriptor device, String body) {
		String responseString = null;
		log.debug("executing HUE api request to set away for nest " + anItem.getType() + ": " + anItem.getItem().toString());
		if(!validNest) {
			log.warn("Should not get here, no Nest available");
			responseString = "[{\"error\":{\"type\": 6, \"address\": \"/lights/" + lightId
					+ "\",\"description\": \"Should not get here, no Nest available\", \"parameter\": \"/lights/"
					+ lightId + "state\"}}]";
		} else if (anItem.getType() != null && anItem.getType().trim().equalsIgnoreCase(DeviceMapTypes.NEST_HOMEAWAY[DeviceMapTypes.typeIndex])) {
			NestInstruction homeAway = aGsonHandler.fromJson(anItem.getItem().toString(), NestInstruction.class);
			theNest.getHome(homeAway.getName()).setAway(homeAway.getAway());
		} else if (anItem.getType() != null && anItem.getType().trim().equalsIgnoreCase(DeviceMapTypes.NEST_THERMO_SET[DeviceMapTypes.typeIndex])) {
				NestInstruction thermoSetting = aGsonHandler.fromJson(anItem.getItem().toString(), NestInstruction.class);
				if (thermoSetting.getControl().equalsIgnoreCase("temp")) {
					if (stateHasBri) {
						if (isFarenheit)
							thermoSetting
									.setTemp(
											String.valueOf((Double
													.parseDouble(BrightnessDecode.calculateReplaceIntensityValue(thermoSetting.getTemp(),
															state, theStateChanges,	stateHasBri, stateHasBriInc, false)) - 32.0) / 1.8));
						else
							thermoSetting
									.setTemp(
											String.valueOf(Double.parseDouble(BrightnessDecode.calculateReplaceIntensityValue(thermoSetting.getTemp(),
													state, theStateChanges,	stateHasBri, stateHasBriInc, false))));
						log.debug("Setting thermostat: " + thermoSetting.getName() + " to "
								+ thermoSetting.getTemp() + "C");
						theNest.getThermostat(thermoSetting.getName())
								.setTargetTemperature(Float.parseFloat(thermoSetting.getTemp()));
					}
				} else if (thermoSetting.getControl().contains("range")
						|| thermoSetting.getControl().contains("heat")
						|| thermoSetting.getControl().contains("cool")
						|| thermoSetting.getControl().contains("off")) {
					log.debug("Setting thermostat target type: " + thermoSetting.getName() + " to "
							+ thermoSetting.getControl());
					theNest.getThermostat(thermoSetting.getName()).setTargetType(thermoSetting.getControl());
				} else if (thermoSetting.getControl().contains("fan")) {
					log.debug("Setting thermostat fan mode: " + thermoSetting.getName() + " to "
							+ thermoSetting.getControl().substring(4));
					theNest.getThermostat(thermoSetting.getName())
							.setFanMode(thermoSetting.getControl().substring(4));
				} else {
					log.warn("no valid Nest control info: " + thermoSetting.getControl());
					responseString = "[{\"error\":{\"type\": 6, \"address\": \"/lights/" + lightId
							+ "\",\"description\": \"no valid Nest control info\", \"parameter\": \"/lights/"
							+ lightId + "state\"}}]";
				}
		}
		log.info("device handler not implemented");
		return responseString;
	}

	@Override
	public com.bwssystems.HABridge.Home createHome(BridgeSettingsDescriptor bridgeSettings) {
        theSession = null;
        theNest = null;
        nestItems = null;
        validNest = bridgeSettings.isValidNest();
		aGsonHandler = null;
        
        if(!validNest) {
        	log.debug("not a valid nest");
        } else {
    		aGsonHandler = new GsonBuilder().create();
	
    		isFarenheit = bridgeSettings.isFarenheit();
	       try {
	            theSession = new NestSession(bridgeSettings.getNestuser(), bridgeSettings.getNestpwd());
	        	theNest = new Nest(theSession);
	        } catch (LoginException e) {
	            log.error("Caught Login Exception, setting Nest to invalid....");
	            validNest = false;
	            theSession = null;
	        }
        }
		return this;
	}
}
