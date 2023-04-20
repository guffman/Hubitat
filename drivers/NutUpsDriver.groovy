/**
 *  NUT UPS Device Type for Hubitat
 *  Péter Gulyás (@guyeeba)
 *  Modified by Guffman 04-19-2023
 *
 *  Usage:
 *  1. Add this code as a device driver in the Hubitat Drivers Code section
 *  2. Set NUT server's IP and credentials
 *  3. ?
 *  4. Profit!
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
 */

metadata {
	definition (name: "NUT UPS Driver", namespace: "Guffman", author: "Peter GULYAS") {
	    capability "Initialize"
        capability "Telnet"
	    capability "Refresh"
        capability "PowerSource"
	    capability "VoltageMeasurement"		
	    capability "Battery"
        capability "Actuator"
	}
	
	attribute "batteryVoltage", "Number"
	attribute "deviceAlarm", "String"
	attribute "batteryRuntime", "Number"
	attribute "upsStatus", "String"
    attribute "upsStatusDescription", "String"
    attribute "beeperStatus", "String"
    attribute "lastUpdated", "string"

    command "execCommand", [[name:"nutCommand",type:"STRING", description:"NUT command to execute"]]
    command "beeperEnable"
    command "beeperDisable"
    command "beeperMute"
    command "close" // to test telnet connect/disconnect code
    
	preferences {
        input name: "nutServerHost", type: "text", title: "NUT server hostname/IP address", required: true
        input name: "nutServerPort", type: "number", title: "NUT server port number", required: true, defaultValue: 3493, range: "1..65535"
        input name: "upsName", type: "text", title: "NUT server UPS name", required: true, defaultValue: "ups"
        input name: "nutServerLoginUsername", type: "text", title: "NUT server username", required: true, defaultValue: hubitat
        input name: "nutServerLoginPassword", type: "password", title: "NUT server password", required: true
        input name: "nutReconnectDelay", type: "number", title: "Network reconnect delay", defaultValue: 15, range: "1..600"
        input name: "nutPollingInterval", type: "number", title: "Polling interval", defaultValue: 30, range: "1..600"
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
        input name: "telnetLogEnable", type: "bool", title: "Enable telnet session message logging", defaultValue: false
        input name: "stateLogEnable", type: "bool", title: "Enable telnet session state logging", defaultValue: false
    }
}

import groovy.transform.Field

def installed(){
    log.info("NUT UPS Driver: installed()")
}

def updated(){
    log.info("NUT UPS Driver: updated()")
    def init = false
    
    // Only re-initialize the telnet connection if necessary
    if (getDataValue("nutServerHost") != nutServerHost) { updateDataValue("nutServerHost", nutServerHost) ; init = true }
    if (getDataValue("nutServerPort") != nutServerPort.toString()) { updateDataValue("nutServerPort", nutServerPort.toString())  ; init = true }
    if (getDataValue("upsName") != upsName) { updateDataValue("upsName", upsName) ; init = true }
    if (getDataValue("nutServerLoginUsername") != nutServerLoginUsername) { updateDataValue("nutServerLoginUsername", nutServerLoginUsername) ; init = true }
    if (getDataValue("nutServerLoginPassword") != nutServerLoginPassword) { updateDataValue("nutServerLoginPassword", nutServerLoginPassword) ; init = true }
	
    log.info "updated(): init=${init}"
    if (init) initialize()
}

def initialize(){
    log.info("NUT UPS Driver: initialize()")
    unschedule()
    state.telnetState = ""
    state.upsData = [:]
    
    // store the key telnet connection previous input values to detect changes in updated()
    updateDataValue("nutServerHost", nutServerHost)
    updateDataValue("nutServerPort", nutServerPort.toString())
    updateDataValue("upsName", upsName)
    updateDataValue("nutServerLoginUsername", nutServerLoginUsername)
    updateDataValue("nutServerLoginPassword", nutServerLoginPassword)
    
	connectToServer()
}

def refresh() {
    if (logEnable) log.debug "refresh() ---"
	setState(STATE_REFRESH)
	sendMsg("LIST VAR ${upsName}")
	setState(STATE_WAITINGFOREVENT)
	runIn(nutPollingInterval, refresh)
}

def telnetStatus(String status) {
	if(logEnable) log.debug "telnetStatus: ${status}"
	if (status == "receive error: Stream is closed" || status == "send error: Broken pipe (Write failed)") {
        setState(STATE_NETWORKERROR, status)
        log.error("Telnet connection dropped: ${status}...attempting reconnection in ${nutReconnectDelay} sec")
		close()
		runIn(nutReconnectDelay, initialize)
    }
}

def close() {
    // wipe any pending refresh and reconnect timers
    unschedule()
    telnetClose()
    setState(STATE_DISCONNECTED)
}

def parse(String message) {
	if (telnetLogEnable) log.info("receiving telnet message: ${message}")

	String[] msg = message.split("\"?( |\$)(?=(([^\"]*\"){2})*[^\"]*\$)\"?")
	
	switch (msg[0]) {
		case "BEGIN":
			parseBEGIN(msg.drop(1))
			break
		case "END":
			parseEND(msg.drop(1))
            updateMessageTimestamp()
			break
		case "UPS":
			parseUPS(msg.drop(1))
			break
		case "VAR":
			parseVAR(msg.drop(1))
			break
		case "OK":
			parseOK(msg.drop(1))
			break
		default:
			log.error("Parse: Couldn't process message: \"${message}\"")
	}
}

def parseBEGIN(String[] msg) {
	switch (msg[0]) {
		case "LIST":
			parseBEGIN_LIST(msg.drop(1))
			break
		default:
			log.error("ParseBEGIN: Couldn't process message: \"${msg}\"")
	}
}

def parseBEGIN_LIST(String[] msg) {
	switch (msg[0]) {
		case "UPS":
			setState(STATE_PROCESSINGUPSLIST)
			break
		case "VAR":
			displayDebugLog "Processing of values for \"${msg[1]}\" started"
			break
		default:
			log.error("ParseBEGIN_LIST: Couldn't process message: \"${msg}\"")
	}
}

def parseEND(String[] msg) {
	switch (msg[0]) {
		case "LIST":
			parseEND_LIST(msg.drop(1))
			break
		default:
			log.error("ParseEND: Couldn't process message: \"${msg}\"")
	}
}

def parseEND_LIST(String[] msg) {
	switch (msg[0]) {
		case "UPS":
			refresh()
			break
		case "VAR":
			displayDebugLog "Processing of values for \"${msg[1]}\" finished"
			break
		default:
			log.error("ParseEND_LIST: Couldn't process message: \"${msg}\"")
	}
}

def parseUPS(String[] msg) {
    displayDebugLog "UPS found with ID: \"${msg[0]}\""
    if (msg[0] != upsName) log.error "NUT UPS ID does not match entered NUT server UPS name"
}

def parseVAR(String[] msg) {
    
  	def result = []
	def key = msg[1]
	def value = msg.length > 1 ? msg[2] : null

	// If no values have changed stop parsing to minimize logging
	if (state.upsData.containsKey(key) && state.upsData.get(key) == value) {
        displayDebugLog "In parseVAR, value key unchanged: ${key}"
		return
	} else {
        def from = state.upsData.get(key)
        displayDebugLog "In parseVAR, value changed key: ${key}, from: ${from}, to: ${value}"
	    state.upsData["${key}".toString()] = value
	}

	switch (key) {
		case "battery.charge":
			result << createEvent(name: 'battery', value: value, unit: "%", descriptionText: "Battery is at ${value}%")
			break;
		case "battery.voltage":
			result << createEvent(name: 'batteryVoltage', value: value, unit: "Volts", descriptionText: "Battery voltage is ${value} Volts")
			break;
		case "battery.runtime":
			result << createEvent(name: 'batteryRuntime', value: value, descriptionText: "Remaining runtime is ${value} sec")
			break;
        case "input.voltage":
			result << createEvent(name: 'voltage', value: value, unit: "Volts",	descriptionText: "Input voltage is ${value} Volts")
			break;
		case "ups.alarm":
			result << createEvent(name: 'deviceAlarm', value: value, descriptionText: "Device alarm is ${value}")
			break;
        case "ups.beeper.status":
			result << createEvent(name: 'beeperStatus', value: value, descriptionText: "Device beeper status is ${value}")
			break;
		case "ups.status":
            result << createEvent(name: 'upsStatus', value: value, descriptionText: "Device status is ${value}")
			def statuses = value.split(" ")
			def powerSource = "unknown"
			if (statuses.contains('OL')) {
				powerSource = "mains"
			} else if (statuses.contains('OB')) {
				powerSource = "battery"
			}

			result << createEvent(name: 'powerSource', value: powerSource, descriptionText: "Power source is ${powerSource}")

			if (!statuses.contains('ALARM') && device.currentValue("deviceAlarm") != "All Clear") {
				displayDebugLog "In parseVAR: Alarm no longer detected."
				result << createEvent(name: 'deviceAlarm', value: 'All Clear', descriptionText: "Alarm removed.")
			}

			def statusCodeMap = [
				'OL': 'Online',
				'OB': 'On Battery',
				'LB': 'Low Battery',
				'HB': 'High Battery',
				'RB': 'Battery Needs Replaced',
				'CHRG': 'Battery Charging',
				'DISCHRG': 'Battery Discharging',
				'BYPASS': 'Bypass Active',
				'CAL': 'Runtime Calibration',
				'OFF': 'Offline',
				'OVER': 'Overloaded',
				'TRIM': 'Trimming Voltage',
				'BOOST': 'Boosting Voltage',
				'FSD': 'Forced Shutdown',
				'ALARM': 'Alarm'
			]

			String statusText = statuses?.collect { statusCodeMap[it] }.join(", ")
			result << createEvent(name: 'upsStatusDescription', value: statusText, descriptionText: "Device status description is ${statusText}")
			break;
	}
	
	return result  
}

def parseOK(String[] msg) {

    switch(state.telnetState) {
		case STATE_AUTH_PHASE1:
		    nutAuthPhase2()
		    break
	    case STATE_AUTH_PHASE2:
		    nutListUPS()
		    break
        case STATE_SENDINGCMD:
            break
	    default:
		    log.error("ParseOK: Couldn't process message: ${msg}")
    }
}

def updateMessageTimestamp(){
    displayDebugLog "updateMessageTimestamp()"
    def ts = new Date()
    def ts_str = ts.format("yyyy-MM-dd HH:mm:ss")  //2020-09-25 18:00:00
    createEvent(name: 'lastUpdated', value: ts_str, descriptionText: "Last message received from NUT server timestamp")
}

def connectToServer() {
  
		try {
            
            if (telnetLogEnable) log.info("connectToServer: Opening telnet connection, host ${nutServerHost}, port ${nutServerPort}")
		    setState(STATE_CONNECTING)
            telnetConnect([termChars:[10]], nutServerHost, nutServerPort.toInteger(), null, null)
            pauseExecution(1000)
            setState(STATE_CONNECTED)
		    sendEvent(name: 'networkStatus', value: "online", descriptionText: "Device network connection status is online")
            if (telnetLogEnable) log.info("connectToServer: telnet connection established to host ${nutServerHost}, port ${nutServerPort}")
        
		    if (isAuthRequired()) {
			    nutAuthPhase1()
		    }
		    else {
			    nutListUPS()
		    }
            
	    } catch(Exception ex) {
            
            setState(STATE_NETWORKERROR)
            log.error("connectToServer: telnetConnect error: ${ex}")
		    sendEvent(name: 'networkStatus', value: "offline", descriptionText: "Device network connection status is offline")
            
	    }
}

def isAuthRequired() {
	if (nutServerLoginUsername != null && nutServerLoginPassword != null) {
		return true
	} else {
		if (nutServerLoginUsername != null || nutServerLoginPassword != null) {
			log.warn "To authenticate to NUT server, both username AND password must be given. Defaulting to unathenticated session"
		}
		return false
	}
}

def nutAuthPhase1() {
	setState(STATE_AUTH_PHASE1)
    pauseExecution(25)
	sendMsg("USERNAME ${nutServerLoginUsername}")
}

def nutAuthPhase2() {
	setState(STATE_AUTH_PHASE2)
    pauseExecution(25)
	sendMsg("PASSWORD ${nutServerLoginPassword}")
}

def nutListUPS() {
	setState(STATE_GETTINGUPSLIST)
    pauseExecution(25)
	sendMsg("LIST UPS")
}

def sendMsg(String msg) {
	if (telnetLogEnable) log.info("sending telnet message: ${msg}")
	def hubCmd = sendHubCommand(new hubitat.device.HubAction("${msg}", hubitat.device.Protocol.TELNET))
    //pauseExecution(50)
    return hubCmd
}

def beeperEnable() {
    setState(STATE_SENDINGCMD)
    pauseExecution(25)
    sendMsg("INSTCMD ${upsName} beeper.enable")
}

def beeperDisable() {
    setState(STATE_SENDINGCMD)
    pauseExecution(25)
    sendMsg("INSTCMD ${upsName} beeper.disable")
}

def beeperMute() {
    setState(STATE_SENDINGCMD)
    pauseExecution(25)
    sendMsg("INSTCMD ${upsName} beeper.mute")
}

def execCommand(cmdStr) {
    setState(STATE_SENDINGCMD)
    pauseExecution(25)
	sendMsg(cmdStr)
}

def setState(String newState) {
    state.telnetState = newState
    if (stateLogEnable) {
        log.info "setState: ${state.telnetState}"
        def descriptionState = "Device telnet session state is ${state.telnetState}"
//        sendEvent(name: 'telnetSessionState', value: state.telnetState, descriptionText: descriptionText)
    }
}

def setState(String newState, String additionalInfo) {
    state.telnetState = newState + " (${additionalInfo})"
    if (stateLogEnable) {
        log.info "setState: ${state.telnetState}"
        def descriptionState = "Device telnet session state is ${state.telnetState}"
//        sendEvent(name: 'telnetSessionState', value: state.telnetState, descriptionText: descriptionText)        
    }

}

private def displayDebugLog(message) {
	if (logEnable) log.debug "${device.displayName}: ${message}"
}

@Field static final String STATE_DISCONNECTED = "Disconnected"
@Field static final String STATE_CONNECTING = "Connecting"
@Field static final String STATE_CONNECTED = "Connected"
@Field static final String STATE_GETTINGUPSLIST = "Getting list of UPSes"
@Field static final String STATE_PROCESSINGUPSLIST = "Processing list of UPSes"
@Field static final String STATE_REFRESH = "Refreshing values"
@Field static final String STATE_SENDINGCMD = "Sending command"
@Field static final String STATE_WAITINGFOREVENT = "Waiting for events to occur"
@Field static final String STATE_NETWORKERROR = "Network Error"
@Field static final String STATE_AUTH_PHASE1 = "Authentication - Phase 1"
@Field static final String STATE_AUTH_PHASE2 = "Authentication - Phase 2"
