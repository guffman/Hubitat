/**
 *  HomeSeer HS-FC200+
 *
 *  Copyright 2018 @Pluckyhd, DarwinsDen.com, HomeSeer, @aruffell 
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *	Author: @pluckyHD, Darwin@DarwinsDen.com, HomeSeer, @aruffell, with fan control button code leveraged from @ChadCK
 *	Date: 2018-18-Oct
 *
 *	Changelog:
 *    5.0 3/30/2019 added 4-speed support (M. Chevis)
 *    4.0 3/29/2019 rules didn't allow double digit buttons so changed numbers accordingly.
 *    3.0 3/29/2019 fixed blink of leds and moves multi push/hold to buttons for better integration in rules.
 *	  2.0 3/26/2019 Initial commit of Hubitat port by @PluckyHD
 *    1.0 Oct 2018 Initial Version based on WD200+
 *
 *   Button Mappings:
 *
 *   ACTION          BUTTON#    BUTTON ACTION
 *   Double-Tap Up     1     	   pressed
 *   Double-Tap Down   2    	   pressed
 *   Triple-Tap Up     3    	   pressed
 *   Triple-Tap Down   4    	   pressed
 *   Hold Up           5 		   pressed
 *   Hold Down         6 		   pressed
 *   Single-Tap Up     7    	   pressed
 *   Single-Tap Down   8    	   pressed
 *   4 taps up         9    	   pressed
 *   4 taps down       10    	   pressed
 *   5 taps up         11    	   pressed
 *   5 taps down       12    	   pressed
 **/

metadata {
    definition(name: "HomeSeer FC200+ Fan Controller V2", namespace: "Guffman", author: "Guffman", importUrl: "https://raw.githubusercontent.com/guffman/Hubitat/master/drivers/HomeSeerFC200FanController.groovy")
	{
        capability "SwitchLevel"
        capability "Actuator"
        capability "Switch"
        capability "Refresh"
        capability "PushableButton"
        capability "Configuration"
        capability "FanControl"

		attribute	"ledMode", "string"
		attribute   "numberOfButtons", "number"
		attribute   "pushed", "number"
		attribute   "btnStat", "string"
        attribute 	"speed", "enum", ["low","medium-low","medium","medium-high","high","on","off","auto"]
        
        
    /*     command "lowSpeed"
		command "medLowSpeed"
        command "medSpeed"
		command "medHighSpeed"
        command "highSpeed" */
		command "setLEDModeToNormal"
		command "setLEDModeToStatus"
        command "setStatusLed", ["integer","integer","integer"]
        command "setBlinkDurationMilliseconds" ,["integer"]
		command "push",["integer"]


        fingerprint mfr: "000C", prod: "0203", model: "0001"

    }

    preferences {
		section("Settings"){
            input "doubleTapToFullSpeed", "bool", title: "Double-Tap Up sets to High speed", defaultValue: false, displayDuringSetup: true, required: false
            input "singleTapToFullSpeed", "bool", title: "Single-Tap Up sets to High speed", defaultValue: false, displayDuringSetup: true, required: false
            input "doubleTapDownToDim", "bool", title: "Double-Tap Down sets to Low speed", defaultValue: false, displayDuringSetup: true, required: false
            input "enable4FanSpeeds", "bool", title: "Enable 4-speed mode", defaultValue: false, displayDuringSetup: true, required: false
            input "reverseSwitch", "bool", title: "Reverse Switch", defaultValue: false, displayDuringSetup: true, required: false
            input "bottomled", "bool", title: "Bottom LED On if Load is Off", defaultValue: false, displayDuringSetup: true, required: false
            input("color", "enum", title: "Default LED Color", options: ["White", "Red", "Green", "Blue", "Magenta", "Yellow", "Cyan"], description: "Select Color", required: false)
		}
		section("Fan Thresholds") {
			input "lowThreshold", "number", title: "Low Threshold", range: "1..99", defaultValue: 25, required: true
            input "medLowThreshold", "number", title: "Medium-Low Threshold", range: "1..99", defaultValue: 49, required: true
			input "medThreshold", "number", title: "Medium Threshold", range: "1..99", defaultValue: 49, required: true
            input "medHighThreshold", "number", title: "Medium-High Threshold", range: "1..99", defaultValue: 74, required: true
			input "highThreshold", "number", title: "High Threshold", range: "1..99", defaultValue: 99, required: true
		}
        section("Logging") {
            input (
                name: "debugMode", 
                type: "bool", 
                title: "Enable debug logging", 
                required: true, 
                defaultValue: false
            )
        }
    }   

}

def parse(String description) {
    if (debugMode) log.debug "Parse: description is ${description}"
    def cmd = zwave.parse(description, [0x20: 1, 0x26: 1, 0x70: 1])
    if (cmd) {zwaveEvent(cmd)}
    if (debugMode) log.debug "Parse: cmd is ${cmd}"
    return
}

// returns on digital
def zwaveEvent(hubitat.zwave.commands.basicv1.BasicReport cmd) {
    if(debugMode) log.debug "BasicReport value: ${cmd.value}"
    dimmerEvents(cmd.value, "digital")
}

// returns on physical
def zwaveEvent(hubitat.zwave.commands.switchmultilevelv1.SwitchMultilevelReport cmd) {
    if (debugMode) log.debug "SwitchMultilevelReport value: ${cmd.value}"
    dimmerEvents(cmd.value, "physical")
}

def zwaveEvent(hubitat.zwave.commands.basicv1.BasicSet cmd) {
    dimmerEvents(cmd)
}

def zwaveEvent(hubitat.zwave.commands.switchmultilevelv1.SwitchMultilevelSet cmd) {
    dimmerEvents(cmd)
}


private dimmerEvents(rawValue, type) {
	
    if (debugMode) {log.debug "dimmerEvents: value: ${rawValue}, type: ${type}"}

    def levelValue = rawValue.toInteger()
    state.lastLevel = levelValue
    state.lastSpeed = device.currentValue("speed")
    sendEvent(name: "level", value: levelValue, unit: "%", descriptionText: "fan level is " + levelValue + "%")

    if (levelValue) {
        if (device.currentValue("switch") == "off") {
			sendEvent(name: "switch", value: "on", isStateChange: true, descriptionText: "$device.displayName is on")
			if (debugMode) log.debug "$device.displayName is on"
		}
    } else {
		if (device.currentValue("switch") == "on") {
			sendEvent(name: "switch", value: "off", isStateChange: true, descriptionText: "$device.displayName is off")
			if (debugMode) log.debug "$device.displayName is off"
		}
	}

        if (enable4FanSpeeds) {
            if (levelValue == 0) (sendEvent([name: "speed", value: "off", displayed: true, descriptionText: "fan speed set to off"]))
            if (levelValue > 0 && levelValue <= lowThreshold) {
                sendEvent([name: "speed", value: "low", displayed: true, descriptionText: "fan speed set to low"])
                state.lastSpeed = "low"
            }
            if (levelValue > lowThreshold && levelValue <= medLowThreshold) {
                sendEvent([name: "speed", value: "medium-low", displayed: true, descriptionText: "fan speed set to medium-low"])
                state.lastSpeed = "medium-low"
            }
            if (levelValue > medLowThreshold && levelValue <= medHighThreshold) {
                sendEvent([name: "speed", value: "medium-high", displayed: true, descriptionText: "fan speed set to medium-high"])
                state.lastSpeed = "medium-high"
            }
	        if (levelValue > medHighThreshold) {
                sendEvent([name: "speed", value: "high", displayed: true, descriptionText: "fan speed set to high"])
                state.lastSpeed = "high"
            }
        } else {
            if (levelValue == 0) (sendEvent([name: "speed", value: "off", displayed: true, descriptionText: "fan speed set to off"]))
            if (levelValue > 0 && levelValue <= lowThreshold) {
                sendEvent([name: "speed", value: "low", displayed: true, descriptionText: "fan speed set to low"])
                state.lastSpeed = "low"
            }
            if (levelValue > lowThreshold && levelValue <= medThreshold) {
                sendEvent([name: "speed", value: "medium", displayed: true, descriptionText: "fan speed set to medium"])
                state.lastSpeed = "medium"
            }
	        if (levelValue > medThreshold) {
                sendEvent([name: "speed", value: "high", displayed: true, descriptionText: "fan speed set to high"])
                state.lastSpeed = "high"
            }
        }
}

def zwaveEvent(hubitat.zwave.commands.configurationv1.ConfigurationReport cmd) {
    if (debugMode) {log.debug "ConfigurationReport $cmd"}
    def value = "when off"
    if (cmd.configurationValue[0] == 1) {
        value = "when on"
    }
    if (cmd.configurationValue[0] == 2) {
        value = "never"
    }
    createEvent([name: "indicatorStatus", value: value])
}

def zwaveEvent(hubitat.zwave.commands.hailv1.Hail cmd) {
    createEvent([name: "hail", value: "hail", descriptionText: "Switch button was pressed", displayed: false])
}

def zwaveEvent(hubitat.zwave.commands.manufacturerspecificv2.ManufacturerSpecificReport cmd) {
    if (debugMode) {
        log.debug "manufacturerId: ${cmd.manufacturerId}"
        log.debug "manufacturerName: ${cmd.manufacturerName}"
    }
    state.manufacturer = cmd.manufacturerName
    if (debugMode) {
        log.debug "productId: ${cmd.productId}"
        log.debug "productTypeId: ${cmd.productTypeId}"
    }

    def msr = String.format("%04X-%04X-%04X", cmd.manufacturerId, cmd.productTypeId, cmd.productId)
    updateDataValue("MSR", msr)
    setFirmwareVersion()
    createEvent([descriptionText: "$device.displayName MSR: $msr", isStateChange: false])
}

def zwaveEvent(hubitat.zwave.commands.versionv1.VersionReport cmd) {
    //updateDataValue(“applicationVersion”, "${cmd.applicationVersion}")
    if (debugMode) {
        log.debug("received Version Report")
        log.debug "applicationVersion: ${cmd.applicationVersion}"
        log.debug "applicationSubVersion: ${cmd.applicationSubVersion}"
    }
    state.firmwareVersion = cmd.applicationVersion + '.' + cmd.applicationSubVersion
    if (debugMode) {
        log.debug "zWaveLibraryType: ${cmd.zWaveLibraryType}"
        log.debug "zWaveProtocolVersion: ${cmd.zWaveProtocolVersion}"
        log.debug "zWaveProtocolSubVersion: ${cmd.zWaveProtocolSubVersion}"
    }
    setFirmwareVersion()
    createEvent([descriptionText: "Firmware V" + state.firmwareVersion, isStateChange: false])
}

def zwaveEvent(hubitat.zwave.commands.firmwareupdatemdv2.FirmwareMdReport cmd) {
    if (debugMode) {
        log.debug("received Firmware Report")
        log.debug "checksum: ${cmd.checksum}"
        log.debug "firmwareId: ${cmd.firmwareId}"
        log.debug "manufacturerId: ${cmd.manufacturerId}" [: ]
    }
}

def zwaveEvent(hubitat.zwave.commands.switchmultilevelv1.SwitchMultilevelStopLevelChange cmd) {
    [createEvent(name: "switch", value: "on"), response(zwave.switchMultilevelV1.switchMultilevelGet().format())]
}

def zwaveEvent(hubitat.zwave.Command cmd) {
    // Handles all Z-Wave commands we aren’t interested in
    if (debugMode) log.debug "zwaveEvent un-handled command: ${cmd}"
    [:]
}

def on() {
//    push("digital",7)
    sendEvent(name: "switch", value: "on", isStateChange: true, descriptionText: "$device.displayName is on")
    return delayBetween([zwave.basicV1.basicSet(value: 0xFF).format(), zwave.basicV1.basicGet().format()], 3000)
}

def off() {
//    push("digital",8)
    sendEvent(name: "switch", value: "off", isStateChange: true, descriptionText: "$device.displayName is off")
    return delayBetween([zwave.basicV1.basicSet(value: 0x00).format(), zwave.basicV1.basicGet().format()], 3000)
}

def setLevel(value, duration) {
	setLevel (value)
}

def setLevel(value) {
    if (debugMode) {log.debug "setLevel >> value: $value"}

    def level = value as Integer
    def currval = device.currentValue("switch")

	if (level > 0 && currval == "off") {
		sendEvent(name: "switch", value: "on", descriptionText: "$device.displayName is on")
	} else if (level == 0 && currval == "on") {
		sendEvent(name: "switch", value: "off", descriptionText: "$device.displayName is off")
	}

    if (debugMode) {log.debug "set_level >> sending dimmer value: ${level} %"} 
    sendEvent(name: "level", value: level, unit: "%", descriptionText: "$device.displayName is " + level + "%")
    delayBetween ([zwave.basicV1.basicSet(value: value).format(), zwave.switchMultilevelV1.switchMultilevelGet().format()], 3000)
}

def setSpeed(newspeed) {

    if (debugMode) {log.debug ("setSpeed command requested: ${newspeed} ")}
    def currval = device.currentValue("switch")

	if (newspeed != "off" && currval == "off") {
		sendEvent(name: "switch", value: "on", descriptionText: "$device.displayName is on")
	} else if (newspeed == "off" && currval == "on") {
		sendEvent(name: "switch", value: "off", descriptionText: "$device.displayName is off")
	}

    switch (newspeed)
    {
        case "off":
            off()
            break;
        case "on":
            on()
            break;
        case "auto":
            if (debugMode) {log.debug("Speed set to Auto - not defined in this device")}
        case "low":
            sendEvent([name: "speed", value: "low", displayed: true, descriptionText: "fan speed set to low"])
	        sendEvent(name: "level", value: lowThreshold, unit: "%", descriptionText: "$device.displayName is " + lowThreshold + "%")
	        delayBetween ([zwave.basicV1.basicSet(value: lowThreshold).format(), zwave.switchMultilevelV1.switchMultilevelGet().format()], 3000)
            break;
        case "medium-low":
            sendEvent([name: "speed", value: "medium-low", displayed: true, descriptionText: "fan speed set to medium-low"])
    	    sendEvent(name: "level", value: medLowThreshold, unit: "%", descriptionText: "$device.displayName is " + medLowThreshold + "%")
	        delayBetween ([zwave.basicV1.basicSet(value: medLowThreshold).format(), zwave.switchMultilevelV1.switchMultilevelGet().format()], 3000)
            break;
        case "medium":
            sendEvent([name: "speed", value: "medium", displayed: true, descriptionText: "fan speed set to medium"])
 	        sendEvent(name: "level", value: medThreshold, unit: "%", descriptionText: "$device.displayName is " + medThreshold + "%")
	        delayBetween ([zwave.basicV1.basicSet(value: medThreshold).format(), zwave.switchMultilevelV1.switchMultilevelGet().format()], 3000)
           break;
        case "medium-high":
            sendEvent([name: "speed", value: "medium-high", displayed: true, descriptionText: "fan speed set to medium-high"])
 	        sendEvent(name: "level", value: medHighThreshold, unit: "%", descriptionText: "$device.displayName is " + medHighThreshold + "%")
	        delayBetween ([zwave.basicV1.basicSet(value: medHighThreshold).format(), zwave.switchMultilevelV1.switchMultilevelGet().format()], 3000)
           break;
        case "high":
            sendEvent([name: "speed", value: "high", displayed: true, descriptionText: "fan speed set to high"])
 	        sendEvent(name: "level", value: highThreshold, unit: "%", descriptionText: "$device.displayName is " + highThreshold + "%")
	        delayBetween ([zwave.basicV1.basicSet(value: highThreshold).format(), zwave.switchMultilevelV1.switchMultilevelGet().format()], 3000)
            break;
    }
}

/*
 *  Set dimmer to status mode, then set the color of the individual LED
 *
 *  led = 1-7
 *  color = 0=0ff
 *          1=red
 *          2=green
 *          3=blue
 *          4=magenta
 *          5=yellow
 *          6=cyan
 *          7=white
 */

def setBlinkDurationMilliseconds(newBlinkDuration) {
    def cmds = []
    if (0 < newBlinkDuration && newBlinkDuration < 25500) {
        if (debugMode) {log.debug "setting blink duration to: ${newBlinkDuration} ms"}
        state.blinkDuration = newBlinkDuration.toInteger() / 100
        if (debugMode) {log.debug "blink duration config parameter 30 is: ${state.blinkDuration}"}
        cmds << zwave.configurationV2.configurationSet(configurationValue: [state.blinkDuration.toInteger()], parameterNumber: 30, size: 1).format()
    } else {
        if (debugMode) {log.debug "commanded blink duration ${newBlinkDuration} is outside range 0 … 25500 ms"}
    }
    return cmds
}

def setStatusLed(led, color, blink) {
    def cmds = []

    if (state.statusled1 == null) {
        state.statusled1 = 0
        state.statusled2 = 0
        state.statusled3 = 0
        state.statusled4 = 0
        state.blinkval = 0
    }

    /* set led # and color */
    switch (led) {
        case 1:
            state.statusled1 = color
            break
        case 2:
            state.statusled2 = color
            break
        case 3:
            state.statusled3 = color
            break
        case 4:
            state.statusled4 = color
            break
        case 0:
        case 5:
            // Special case - all LED's
            state.statusled1 = color
            state.statusled2 = color
            state.statusled3 = color
            state.statusled4 = color
            break

    }

    if (state.statusled1 == 0 && state.statusled2 == 0 && state.statusled3 == 0 && state.statusled4 == 0 && state.statusled5 == 0 && state.statusled6 == 0 && state.statusled7 == 0) {
        // no LEDS are set, put back to NORMAL mode
        cmds << zwave.configurationV2.configurationSet(configurationValue: [0], parameterNumber: 13, size: 1).format()
    } else {
        // at least one LED is set, put to status mode
        cmds << zwave.configurationV2.configurationSet(configurationValue: [1], parameterNumber: 13, size: 1).format()
    }

    if (led == 5 | led == 0) {
        for (def ledToChange = 1; ledToChange <= 4; ledToChange++) {
            // set color for all LEDs
            cmds << zwave.configurationV2.configurationSet(configurationValue: [color], parameterNumber: (ledToChange + 20), size: 1).format()
        }
    } else {
        // set color for specified LED
			cmds << zwave.configurationV2.configurationSet(configurationValue: [color], parameterNumber: (led + 20), size: 1).format()
	}

    // check if LED should be blinking
    def blinkval = state.blinkval
	if (debugMode) {log.debug "current blinkval $blinkval , blink setting $blink)"}
    if (blink==1) {
		
        switch (led) {
            case 1:
                blinkval = blinkval | 0x1
                break
            case 2:
                blinkval = blinkval | 0x2
                break
            case 3:
                blinkval = blinkval | 0x4
                break
            case 4:
                blinkval = blinkval | 0x8
                break
            case 0:
            case 5:
                blinkval = 0x7F
                break
        }
        cmds << zwave.configurationV2.configurationSet(configurationValue: [blinkval], parameterNumber: 31, size: 1).format()
		if (debugMode) {log.debug "New blinkval $blinkval"}
        state.blinkval = blinkval
        // set blink frequency if not already set, 5=500ms
        if (state.blinkDuration == null | state.blinkDuration < 0 | state.blinkDuration > 255) {
            cmds << zwave.configurationV2.configurationSet(configurationValue: [5], parameterNumber: 30, size: 1).format()
        }
    } else {

        switch (led) {
            case 1:
                blinkval = blinkval & 0xFE
                break
            case 2:
                blinkval = blinkval & 0xFD
                break
            case 3:
                blinkval = blinkval & 0xFB
                break
            case 4:
                blinkval = blinkval & 0xF7
                break
            case 0:
            case 5:
				if (debugMode) {log.debug "delete blink value"}
                blinkval = 0
                break
        }
        cmds << zwave.configurationV2.configurationSet(configurationValue: [blinkval], parameterNumber: 31, size: 1).format()
        state.blinkval = blinkval
		if (debugMode) {log.debug "New blinkval $blinkval"}
    }
    delayBetween(cmds, 150)

}

/*
 * Set Dimmer to Status mode (exit normal mode)
 *
 */

def setLEDModeToNormal() {
    def cmds = []
	sendEvent(name: "ledMode", value: "Normal",isStateChange: true)
    cmds << zwave.configurationV2.configurationSet(configurationValue: [0], parameterNumber: 13, size: 1).format()
    delayBetween(cmds, 500)
}

/*
 * Set the color of the LEDS for normal dimming mode, shows the current dim level
 */
def setLEDModeToStatus() {
    def cmds = []
	sendEvent(name: "ledMode", value: "Status",isStateChange: true)
    cmds << zwave.configurationV2.configurationSet(configurationValue: [1], parameterNumber: 13, size: 1).format()
    delayBetween(cmds, 500)
	
}

/*
 *   Set the color of the LEDS for normal fan speed mode, shows the current speed
 */

def setDefaultColor(color) {
    def cmds = []
    cmds << zwave.configurationV2.configurationSet(configurationValue: [color], parameterNumber: 14, size: 1).format()
    delayBetween(cmds, 500)
}

def poll() {
    zwave.switchMultilevelV1.switchMultilevelGet().format()
}

def refresh() {
    if (debugMode) {log.debug "refresh() called"}
    return [zwave.basicV1.basicGet().format()]
}

def zwaveEvent(hubitat.zwave.commands.centralscenev1.CentralSceneNotification cmd) {
    if (debugMode) {log.debug("sceneNumber: ${cmd.sceneNumber} keyAttributes: ${cmd.keyAttributes}")}
    def result = []

    switch (cmd.sceneNumber) {
        case 1:
            // Up
            switch (cmd.keyAttributes) {
                case 0:
                    // Press Once
                    result += createEvent(push("physical",4))
                    result += createEvent([name: "switch", value: "on", type: "physical"])

                    if (singleTapToFullSpeed) {
                        result += setSpeed("high")
                        result += response("delay 5000")
                        result += response(zwave.switchMultilevelV1.switchMultilevelGet())
                    }
                    break
                case 1:
                    result = createEvent([name: "switch", value: "on", type: "physical"])
                    break
                case 2:
                    // Hold
                    result += createEvent(push("physical",5))
                    result += createEvent([name: "switch", value: "on", type: "physical"])
                    break
                case 3:
                    // 2 Times
                    result += createEvent(push("physical",1))
                    if (doubleTapToFullSpeed) {
                        result += setSpeed("high")
                        result += response("delay 5000")
                        result += response(zwave.switchMultilevelV1.switchMultilevelGet())
                    }
                    break
                case 4:
                    // 3 times
                    result += createEvent(push("physical",3))
                    break
                case 5:
                    // 4 times
                    result += createEvent(push("physical",9))
                    break
                case 6:
                    // 5 times
                    result += createEvent(push("physical",11))
                    break
                default:
                    if (debugMode) {log.debug("unexpected up press keyAttribute: $cmd.keyAttributes")}
            }
            break

        case 2:
            // Down
            switch (cmd.keyAttributes) {
                case 0:
                    // Press Once
                    result += createEvent(push("physical",8))
                    result += createEvent([name: "switch", value: "off", type: "physical"])
                    break
                case 1:
                    result = createEvent([name: "switch", value: "off", type: "physical"])
                    break
                case 2:
                    // Hold
                    result += createEvent(push("physical",6))
                    result += createEvent([name: "switch", value: "off", type: "physical"])
                    break
                case 3:
                    // 2 Times
                    createEvent(push("physical",2))
                    if (doubleTapDownToDim) {
                        result += setSpeed("low")
                        result += response("delay 5000")
                        result += response(zwave.switchMultilevelV1.switchMultilevelGet())
                    }
                    break
                case 4:
                    // 3 Times
                    result += createEvent(push("physical",4))
                    break
                case 5:
                    // 4 Times
                    result += createEvent(push("physical",10))
                    break
                case 6:
                    // 5 Times
                    result += createEvent(push("physical",12))
                    break
                default:
                    if (debugMode) {log.debug("unexpected down press keyAttribute: $cmd.keyAttributes")}
            }
            break

        default:
            // unexpected case
            log.debug("unexpected scene: $cmd.sceneNumber")

    }
    return result
}

def push(btnNum)
{
   if (debugMode) {log.debug "Button pushed $btnNum"}
   push("Digital",btnNum)
}

// Valid button numbers are 10-16 and 20-26 first number 1 indicates up and 2 indicates down. Second number 0 = pushed once

def push(String buttonType, btnNum) {
	
	def statValue = ""
	
	switch(btnNum){

		
				case 1:
				 // 2 Times
						statValue = "Tap ▲▲"
						break
		
				case 2:
				 // 2 Times
						statValue = "Tap ▼▼"
						break
				case 3:
				// 3 times
						statValue = "Tap ▲▲▲"
						break
				case 4:
				// 3 times
						statValue = "Tap ▼▼▼"
						break
		
				case 5:
				//Hold 
						statValue = "Hold ▲"
						break
		
				case 6:
				//Hold 
						statValue = "Hold ▼"
						break
		
				case 7:
						statValue ="Tap ▲"
						break
		
				case 8:
						statValue ="Tap ▼"
						break		
				
				case 9:
				// 4 times
						statValue = "Tap ▲▲▲▲"
						break
				case 10:
				// 4 times
						statValue = "Tap ▼▼▼▼"
						break
			
				case 11: 
				// 5 times
						statValue = "Tap ▲▲▲▲▲"
						break

				case 12: 
				// 5 times
						statValue = "Tap ▼▼▼▼▼ "
						break
		
				default:
				// unexpected case
					log.debug("unexpected button number: $btnNum")
				break
			 }
		

		
    sendEvent(name: "pushed", value: btnNum,data: [buttonNumber: btnNum], descriptionText: "$device.displayName button $btnNum was pushed",  type: "$buttonType", isStateChange: true)
	sendEvent(name: "btnStat", value: statValue, isStateChange: true)
}

def setFirmwareVersion() {
    def versionInfo = ''
    if (state.manufacturer) {
        versionInfo = state.manufacturer + ' '
    }
    if (state.firmwareVersion) {
        versionInfo = versionInfo + "Firmware V" + state.firmwareVersion
    } else {
        versionInfo = versionInfo + "Firmware unknown"
    }
    sendEvent(name: "firmwareVersion", value: versionInfo, isStateChange: true, displayed: false)
}

def configure() {
    if (debugMode) {log.debug("configure() called")}

    sendEvent(name: "numberOfButtons", value: 12, displayed: true)
    def commands = []
    setPrefs()
    commands << zwave.switchMultilevelV1.switchMultilevelGet().format()
    commands << zwave.manufacturerSpecificV1.manufacturerSpecificGet().format()
    commands << zwave.versionV1.versionGet().format()
	if (debugMode) {log.debug commands}
    delayBetween(commands, 500)
}

def setPrefs() {
    if (debugMode) {log.debug("set prefs")}
    def cmds = []

    state.lastSpeed = "low"

    if (debugMode) {log.debug("set prefs >> ${settings.lowThreshold} ${settings.medLowThreshold} ${settings.medThreshold} ${settings.medHighThreshold} ${settings.highThreshold}")}

    if (color) {
        switch (color) {
            case "White":
                cmds << zwave.configurationV2.configurationSet(configurationValue: [0], parameterNumber: 14, size: 1).format()
                break
            case "Red":
                cmds << zwave.configurationV2.configurationSet(configurationValue: [1], parameterNumber: 14, size: 1).format()
                break
            case "Green":
                cmds << zwave.configurationV2.configurationSet(configurationValue: [2], parameterNumber: 14, size: 1).format()
                break
            case "Blue":
                cmds << zwave.configurationV2.configurationSet(configurationValue: [3], parameterNumber: 14, size: 1).format()
                break
            case "Magenta":
                cmds << zwave.configurationV2.configurationSet(configurationValue: [4], parameterNumber: 14, size: 1).format()
                break
            case "Yellow":
                cmds << zwave.configurationV2.configurationSet(configurationValue: [5], parameterNumber: 14, size: 1).format()
                break
            case "Cyan":
                cmds << zwave.configurationV2.configurationSet(configurationValue: [6], parameterNumber: 14, size: 1).format()
                break
        }
    }

    if (reverseSwitch) {
        cmds << zwave.configurationV2.configurationSet(configurationValue: [1], parameterNumber: 4, size: 1).format()
    } else {
        cmds << zwave.configurationV2.configurationSet(configurationValue: [0], parameterNumber: 4, size: 1).format()
    }

    if (bottomled) {
        cmds << zwave.configurationV2.configurationSet(configurationValue: [0], parameterNumber: 3, size: 1).format()
    } else {
        cmds << zwave.configurationV2.configurationSet(configurationValue: [1], parameterNumber: 3, size: 1).format()
    }

    //Sets fan type
    if (enable4FanSpeeds) {
        //4 Speeds
        cmds << zwave.configurationV2.configurationSet(configurationValue: [1], parameterNumber: 5, size: 1).format()
    } else {
        //3 Speeds
        cmds << zwave.configurationV2.configurationSet(configurationValue: [0], parameterNumber: 5, size: 1).format()
    }

    //Enable the following configuration gets to verify configuration in the logs
    cmds << zwave.configurationV1.configurationGet(parameterNumber: 5).format()
    cmds << zwave.configurationV1.configurationGet(parameterNumber: 7).format()
    cmds << zwave.configurationV1.configurationGet(parameterNumber: 8).format()
    cmds << zwave.configurationV1.configurationGet(parameterNumber: 9).format()
    cmds << zwave.configurationV1.configurationGet(parameterNumber: 10).format()
	
    delayBetween(cmds, 500)
}

def updated() {
    def cmds = []
}
