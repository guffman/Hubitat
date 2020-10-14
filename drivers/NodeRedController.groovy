/**
 *  Node-RED Controller Switch
 *
 *  Copyright 2018 Daniel Ogorchock
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
 *  Change History:
 *
 *    Date        Who             What
 *    ----        ---             ----
 *    2018-02-18  Dan Ogorchock   Original Creation
 *    2019-10-11  Stephan Hackett Added ContentType and Body inputs(very limited functionality)
 *    2019-12-26  Daniel Terryn	  Added extra header setting
 *    2020-04-21  Guffman         Modified, enhanced to manage a Node-RED instance. Changed from switch to pushbuttons
 *    2020-10-14  Guffman         Added attributes for http response, import Url 
 */
metadata {
	definition (name: "Node-RED Controller", namespace: "Guffman", author: "Guffman", importUrl: "https://raw.githubusercontent.com/guffman/Hubitat/master/drivers/NodeRedController.groovy") {
        capability "Sensor"
        capability "Refresh"
        capability "Actuator"
        capability "Momentary"
        
        attribute "responseStatus", "number"     // http response
        attribute "lastUpdated", "string"        // last successful query
        
        command    "reloadFlows"
        command    "querySettings"
        command    "queryNodes"
	}

	preferences {
		input(name: "deviceIP", type: "string", title:"Node-RED IP Address", required: true, displayDuringSetup: true)
		input(name: "devicePort", type: "string", title:"Node-RED Port", defaultValue: "1880", required: true, displayDuringSetup: true)
        input "logEnable", "bool", title: "Enable debug logging", defaultValue: true
	}
}

def parse(String description) {
    if(logEnable) log.debug "raw response: ${description}"
}

def installed() {
    initialize()
}

def initialize() {
    if (logEnable) log.debug "Initialize"
    configure()
}

def configure() {
    if (logEnable) {
        log.debug "Configure"
	    log.debug "debug logging is enabled."
	    runIn(1800,logsOff)
    }
    state.nrURI = "http://" + deviceIP + ":" + devicePort
    if (!state.lastRestart) state.lastRestart = " "
    querySettings()
    unschedule()
    runEvery5Minutes(refresh)
}

def updated() {
    if (logEnable) log.debug "Updated"
    configure()
}

def refresh() {
    if (logEnable) "Refresh handler - query Node-RED settings"
    querySettings()
}

def push() {
    if (logEnable) "Push handler - reload and restart all flows"
    reloadFlows("reload")
}

def reloadFlows() {
    // Reload command, full || nodes || flows || reload
    def type = "reload"
    def hdr = [:]
    hdr.put("Node-RED-Deployment-Type", type)
    def requestParams =
	    [
		    uri: state.nrURI,
		    requestContentType: "application/json",
            contentType: "application/json",
            headers: hdr,
            path: "/flows",
            timeout: "10"
	    ]

    if (logEnable) log.debug "requestParams ${requestParams}"
	httpPost(requestParams)
	{
	    response ->
		if (response?.status == 200 || response?.status == 204) {
            if (logEnable) log.debug "result: ${response.data}"
            sendEvent(name: "push", isStateChange: true)
            sendUpdatedEvent()
		}
		else {
			log.warn "${response?.status}"
		}
	}
}

def querySettings() {
    // Query nodered version, etc.
    def requestParams =
	    [
		    uri: state.nrURI,
		    requestContentType: "application/json",
            contentType: "application/json",
            path: "/settings",
            timeout: "5"
	    ]
    
    if (logEnable) log.debug "requestParams ${requestParams}"
	httpGet(requestParams)
	{
	    response ->
		if (response?.status == 200) {
            if (logEnable) log.debug "result: ${response.data}"
            state.httpNodeRoot = response.data.httpNodeRoot
            state.version = response.data.version
            sendUpdatedEvent()
		}
		else {
			log.warn "${response?.status}"
		}
        sendEvent(name: "responseStatus", value: response?.status)
	}
}

def queryNodes() {
    // Query nodered installed nodes info
    def requestParams =
	    [
		    uri: state.nrURI,
		    requestContentType: "application/json",
            contentType: "application/json",
            path: "/nodes",
            timeout: "10"
	    ]

    if (logEnable) log.debug "requestParams ${requestParams}"
	httpGet(requestParams)
	{
	    response ->
		if (response?.status == 200) {
            if (logEnable) log.debug "result: ${response.data}"
            sendUpdatedEvent()
		}
		else {
			log.warn "${response?.status}"
		}
        sendEvent(name: "responseStatus", value: response?.status)
	}
}

def sendUpdatedEvent() {
    def date = new Date()
    dateStr = date.format("MM/dd/yyyy HH:mm:ss")
    sendEvent(name: "lastUpdated", value: dateStr)
}

def logsOff() {
    log.warn "debug logging disabled..."
    device.updateSetting("logEnable",[value:"false",type:"bool"])
}
