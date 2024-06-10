import groovy.transform.Field
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.json.JsonBuilder
import java.text.SimpleDateFormat

/*
*  HVAC Setpoint Scheduler
*
*  Adjusts various HVAC setpoints based on time of day and outside temperature 
*    - Use a 0 to +3 degF bias based on outside temperature in exceedance of 90 degF
*    - Use a table of setpoints based on time of day and a "Goodnight" event
*    - Supports Temperature, Dewpoint, and Humidity setpoints
*    - Simulates "circulate" fan mode if not supported directly by the thermostat
*
*  Copyright 2023 Guffman Ventures LLC
*  GNU General Public License v2 (https://www.gnu.org/licenses/gpl-2.0.txt)
*
*  Change History:
*
*    Date        Who            What
*    ----        ---            ----
*    2023-10-16  Marc Chevis    Original Creation
*    2023-10-22  Marc Chevis    Added Setpoint File to input section
*    2023-10-24  Marc Chevis    Changed from virtual switch for circ request to variable reflecting scheduled fan mode
*    2023-11-04  Marc Chevis    Added setpoint table view/edit features
*    2023-11-21  Marc Chevis    Added inputs for suspending the schedule, schedule table editor
*    2024-05-29  Marc Chevis    Revised test for setpoint bias 
*/

definition(
    name: "HVAC Setpoint Scheduler V2",
    namespace: "Guffman",
    author: "Guffman",
    description: "Adjusts HVAC setpoints based on time of day and outdoor temperature.",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/guffman/Hubitat/master/apps/HVACSetpointSchedulerV2.groovy"
)

preferences {
    page(name: "mainPage")
    page(name: "selectPage")
    page(name: "copyPage")
    page(name: "deletePage")
    page(name: "editPage")
    page(name: "jsonEditorPage")
}
def mainPage() {
    
	dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        
        log.info "+++ mainPage:"
       
        if (state.editAction) {
            log.info "+++ mainPage: Got state.editAction, selection = $state.selection"
            section() {
                editPage()
            }
        } else if (state.editKeyAction) {
            log.info "+++ mainPage: Got state.editKeyAction, selection = $state.selection"
            section() {
                editKeyPage()
            }
        } else {
            if (state.selectAction) {
                log.info "+++ mainPage: Got state.selectAction, selection = $state.selection"
                selectRecord()
            } else if (state.editPending) {
                log.info "+++ mainPage: Got state.editPending, selection = $state.selection"
                writeRecord()
            } else if (state.editKeyPending) {
                log.info "+++ mainPage: Got state.editKeyPending, selection = $state.selection"
                writeKey()
            } else if (state.enableAction) {
                log.info "+++ mainPage: Got state.enableAction, selection = $state.selection"
                enableRecord()
            } else if (state.copyAction) {
                log.info "+++ mainPage: Got state.copyAction, selection = $state.selection"
                copyRecord()
            } else if (state.deleteAction) {
                log.info "+++ mainPage: Got state.deleteAction, selection = $state.selection"    
                deleteRecord()
            }
            
            section("<b>General</b>") {
		        input (name: "customLabel", type: "text", title: "Enter a name for the app (optional)", required: false, defaultValue: "HVAC Setpoint Scheduler", submitOnChange: true)
                if(customLabel) app.updateLabel("$customLabel")
            }
            section("<b>Device Configuration</b>") {
                input "tstatDevice", "capability.thermostat", title: "Physical Thermostat:", required: true, multiple: false, width: 6
                input "controllerDevice", "capability.thermostat", title: "Controller Thermostat:", required: true, multiple: false, width: 6
                input "fanModeVariable", "capability.variable", title: "Write Requested Fan Mode To:", required: true, multiple: false
                input "dewpointDevice", "capability.temperatureMeasurement", title: "Write Dewpoint Setpoint To:", required: true, multiple: false
                input "humidityDevice", "capability.relativeHumidityMeasurement", title: "Write Humidity Setpoint To:", required: false, multiple: false
			    input "outdoorTempDevice", "capability.temperatureMeasurement", title: "Outdoor Temperature Sensor:", required: true, multiple: false
                input "suspendSchedulingSwitches", "capability.switch", title: "Suspend Scheduling When Any of These Switches are On:", required: false, multiple: true
                input "suspendTempBiasSwitch", "capability.switch", title: "Suspend Outdoor Temperature Bias When Any of These Switches are On:", required: false, multiple: false
            }

            section("<b>Schedule Configuration</b>") {
                getSchedule()
                paragraph displayTable()
                if (state.selection) href(name: "er" ,title: "Edit Selected Record...", page: "editPage", description: "")
            }
            
            section("<b>Logging</b>") {                
			    input("logLevel", "enum", title: "Logging level", multiple: false, required: true, options: [["0":"None"],["1":"Info"],["2":"Debug"]], defaultValue: "0")  
		    }
        }
    }
}

def selectPage() {
    dynamicPage(name: "selectPage", title: "Select Page", nextPage: "mainPage", uninstall: false) {
        
        log.info "+++ selectPage:"
        log.info "+++ selectPage: state.selection = $state.selection"
        state.selectAction = false
    
        section() {
            paragraph "This is a selectPage, state.selectAction = $state.selectAction, state.selection = $state.selection"
        }
    }
}

def copyPage() {
    dynamicPage(name: "copyPage", title: "Copy Page", nextPage: "mainPage", uninstall: false) {
        
        log.info "+++ copyPage:"
        log.info "+++ copyPage: state.selection = $state.selection"
        copyRecord()
        state.copyAction = false
    
        section() {
            paragraph "Copy successful, state.copyAction = $state.copyAction"
        }
    }
}

def deletePage() {
    dynamicPage(name: "deletePage", title: "Delete Page", nextPage: "mainPage", uninstall: false) {
        
        log.info "+++ deletePage:"
        log.info "+++ deletePage: state.selection = $state.selection"
        deleteRecord()
        state.deleteAction = false
    
        section() {
            paragraph "Delete successful, state.deleteAction = $state.deleteAction"
        }
    }
}

def editPage() {
    dynamicPage(name: "editPage", title: "Record Editor", nextPage: "mainPage", uninstall: false) {
        
        log.info "+++ editPage:"
        log.info "+++ editPage: state.selection = $state.selection, state.editAction = $state.editAction"
        state.editPending = true
        state.editAction = false
        
        // Extract record field values from the selected record and type convert
        def rec = [:]
        def table = new JsonSlurper().parseText(state.json)
        rec = table[state.selection]
        int rec_heating = rec["heating"].toInteger()
        int rec_cooling = rec["cooling"].toInteger()
        int rec_dewpoint = rec["dewpoint"].toInteger()
        int rec_humidity = rec["humidity"].toInteger()
        def rec_fan_mode = rec["fan_mode"].toString()
        def rec_enabled = rec["enabled"].toBoolean()
        def rec_valid = rec["valid"].toBoolean()
                
        // Update the app settings with the record field values
        app.updateSetting("hhmm", state.selection)
        app.updateSetting("heating", rec_heating)
        app.updateSetting("cooling", rec_cooling)
        app.updateSetting("dewpoint", rec_dewpoint)
        app.updateSetting("humidity", rec_humidity)
        app.updateSetting("fan_mode", rec_fan_mode)
        app.updateSetting("enabled", rec_enabled)
        
        // Define Input elements for each record field
        section() {   
            input "enabled", "bool", title: "Enable:", required: true, defaultValue: true, width: 2, submitOnChange: false
        }
        section() {
            input "hhmm", "text", title: "Schedule Time:", required: true, width: 2, submitOnChange: false
            input "cooling", "number", title: "Cooling Setpoint:", required: true, width: 2, submitOnChange: false
            input "heating", "number", title: "Heating Setpoint:", required: true, width: 2, submitOnChange: false
            input "fan_mode", "enum", title: "Fan Mode:", width: 2, multiple: false, required: true, options: [["auto":"auto"],  ["on":"on"], ["circ":"circ"]]
            input "dewpoint", "number", title: "Dewpoint Setpoint:", required: true, width: 2, submitOnChange: false
            input "humidity", "number", title: "Humidity Setpoint:", required: true, width: 2, submitOnChange: false
        }
    }
}

def editKeyPage() {
    dynamicPage(name: "editKeyPage", title: "Time Editor", nextPage: "mainPage", uninstall: false) {
        
        log.info "+++ editKeyPage:"
        log.info "+++ editKeyPage: state.selection = $state.selection, state.editKeyAction = $state.editKeyAction"
        state.editKeyPending = true
        state.editKeyAction = false
                
        // Update the app settings
        datestr = Date.parse('HH:mm', state.selection).format("yyyy-MM-dd'T'HH:mm:ss.sssXX")
        app.updateSetting("tod", datestr)
        log.info "+++ editKeyPage: converted $state.selection to $datestr"
        
        // Define Input elements
        section() {
            input "tod", "time", title: "Schedule Time of Day:", required: false, width: 2, submitOnChange: false
        }
    }
}

def selectRecord() {
    
    // No action to take, persist selection
    
    if (state.selectAction) {    
        log.info "+++ selectRecord: record selected = ${state.selection}"
        
        // reset flag for mainPage
        state.selectAction = false
    }
}

def enableRecord() {
    
    // Toggle the enable/disable state
    
    if (state.enableAction) {    
        def table = new JsonSlurper().parseText(state.json)     
        def rec = [:]
        rec = table[state.selection]
        def currentState = rec["enabled"]
        rec["enabled"] = currentState ? false : true
        log.info("+++ enableRecord: toggled enabled rec is $rec")
        
        // update value at key 'state.selection'
        log.info "+++ writeRecord: updating existing record ${state.selection}"
        table.put(state.selection, rec)
     
        state.json = JsonOutput.toJson(table)
    
        // reset flag for mainPage
        state.enableAction = false
        state.selection = null
    }
}

def copyRecord() {
    
    // Duplicate the selected record with a key +1 min
    if (state.copyAction) {    
        def table = new JsonSlurper().parseText(state.json)     
        def copyKey = addMinute(state.selection)
        table["$copyKey"] = table[state.selection]
        log.info("+++ copyRecord: added new record for $copyKey")
    
        state.json = JsonOutput.toJson(table)
    
        // reset flag for mainPage
        state.copyAction = false
        state.selection = null
    }
}

def deleteRecord() {
    
    // Delete the selected record
    
    if (state.deleteAction) {
        def table = new JsonSlurper().parseText(state.json)
    
        if (table.size() > 1) table.remove(state.selection)
    
        state.json = JsonOutput.toJson(table)
        log.info("+++ deleteRecord: removed record ${state.selection}")
    
        // reset flag for mainPage
        state.deleteAction = false
        state.selection = null
    }
}

def writeRecord() {
    
    // If editPending flag set by the editPage, write the selected record. If key changed, delete key 'state.selection' and add a new key-value pair. 
    log.info "+++ writeRecord:"
    
    if (state.editPending) {
        def table = new JsonSlurper().parseText(state.json)    
        def rec = [:]
        rec = table[state.selection]
        int rec_heating = rec["heating"].toInteger()
        int rec_cooling = rec["cooling"].toInteger()
        int rec_dewpoint = rec["dewpoint"].toInteger()
        int rec_humidity = rec["humidity"].toInteger()
        def rec_fan_mode = rec["fan_mode"].toString()
        def rec_enabled = rec["enabled"]
        def keyChange = (state.selection != hhmm)
        def valueChange = (heating != rec_heating) || (cooling != rec_cooling) || (dewpoint != rec_dewpoint) || (humidity != rec_humidity) || (fan_mode != rec_fan_mode) || (enabled != rec_enabled)
        def isValid = (validTime(state.selection) && ((rec_cooling-rec_heating)>=2) && (rec_dewpoint >= 50) && (rec_humidity >= 50) && (rec_fan_mode != null))
        log.info "+++ writeRecord: isValid = ${isValid}"

        def newValue = [:]
        newValue = ["heating":heating, "cooling":cooling, "dewpoint":dewpoint, "humidity":humidity, "fan_mode":fan_mode, "enabled":enabled, "valid":isValid]

        // If any edits, rewrite the record with input values, render to state.record
        if (keyChange) {
            // Write new key-value, delete key 'select'
            log.info "+++ writeRecord: writing new record ${hhmm}"
            table[hhmm] = newValue
            table.remove(state.selection)
        } else if (!keyChange && valueChange) {
            // update value at key 'select'
            log.info "+++ writeRecord: updating existing record ${state.selection}"
            table.put(state.selection, newValue)
        }
        
        state.json = JsonOutput.toJson(table)

        // reset flag for mainPage
        state.editPending = false
        state.selection = null
    }
}

def writeKey() {
    
    // Duplicate the selected record with a new key derived from the editKeyPage inputs
    log.info "+++ writeKey:"
    
    if (state.editKeyPending) {    
        def table = new JsonSlurper().parseText(state.json)
        def rec = [:]
        rec = table[state.selection]
        
        // compose hhmm from tod input
        log.info "+++ writeRecord: tod = $tod"
        newKey = Date.parse("yyyy-MM-dd'T'HH:mm:ss.sssXX", tod).format('HH:mm')
        if (state.selection != newKey) {
            // Write new key-value, delete key 'select'
            log.info "+++ writeRecord: writing new record ${newKey}"
            table[newKey] = rec
            table.remove(state.selection)
        }
    
        state.json = JsonOutput.toJson(table)
    
        // reset flag for mainPage
        state.editKeyPending = false
        state.selection = null
    }
}

def installed()
{
	initialize()
}

def updated()
{
    infolog "+++ Updated: state.json: ${state.json}"
	unsubscribe()
    unschedule()
	initialize()
}

def initialize()
{
    log.info "+++ initialize: state.json: ${state.json}"
    
    // state variable init
    state.selection = null
    state.selectAction = false
    state.editPending = false
    state.editAction = false
    state.editKeyAction = false
    state.copyAction = false
    state.deleteAction = false
    state.enableAction = false
    
    // load schedule
    getSchedule()
        
    // Controller thermostat in control (on/off)
    state.controlling = (controllerDevice.currentValue("switch") == "on") ? true : false
    subscribe(controllerDevice, "switch", controllerDeviceHandler)
    
    // Controller thermostat mode (heat/cool/auto)
    state.controllerThermostatMode = controllerDevice.currentValue("thermostatMode")
    subscribe(controllerDevice, "thermostatMode", controllerDeviceHandler)
    
    // Thermostat mode (heat/cool/auto)
    state.tstatThermostatMode = tstatDevice.currentValue("thermostatMode")
    subscribe(tstatDevice, "thermostatMode", tstatDeviceHandler)
        
    // Thermostat fan circulate mode - thermostat scheduler requested state (auto/on/circ)
    state.fanModeRequested = fanModeVariable.currentValue("variable")
    subscribe(fanModeVariable, "variable", fanModeVariableHandler)
 
    // Outdoor temperature
    state.outdoorTemp = outdoorTempDevice.currentValue("temperature")
    subscribe(outdoorTempDevice, "temperature", outdoorTempHandler)
    
    // Outdoor temperature bias enabled/disabled
    state.tempBias = 0.0
    if (suspendTempBiasSwitch != null) {
        state.suspendTempBias = (suspendTempBiasSwitch.currentValue("switch") == "on") ? true : false
        subscribe(suspendTempBiasSwitch, "switch", suspendTempBiasSwitchHandler)
    } else {
        state.suspendTempBias = false

    }

    // All-purpose suspend setpoint scheduling switches
    if (suspendSchedulingSwitches != null) subscribe(suspendSchedulingSwitches, "switch", suspendSchedulingSwitchHandler)

    // Current setpoint values
    state.controllerTempSetpoint = controllerDevice.currentValue("thermostatSetpoint")
    subscribe(controllerDevice, "thermostatSetpoint", controllerDeviceHandler)

    state.controllerCoolingSetpoint = controllerDevice.currentValue("coolingSetpoint")
    subscribe(controllerDevice, "coolingSetpoint", controllerDeviceHandler)

    state.controllerHeatingSetpoint = controllerDevice.currentValue("heatingSetpoint")
    subscribe(controllerDevice, "heatingSetpoint", controllerDeviceHandler)

    state.tstatTempSetpoint = tstatDevice.currentValue("thermostatSetpoint")
    subscribe(tstatDevice, "thermostatSetpoint", tstatDeviceHandler)
        
    state.tstatCoolingSetpoint = tstatDevice.currentValue("coolingSetpoint")
    subscribe(tstatDevice, "coolingSetpoint", tstatDeviceHandler)

    state.tstatHeatingSetpoint = tstatDevice.currentValue("heatingSetpoint")
    subscribe(tstatDevice, "heatingSetpoint", tstatDeviceHandler)

    state.dewpointSetpoint = dewpointDevice.currentValue("temperature")
    state.humiditySetpoint = humidityDevice.currentValue("humidity")
    
    // Set app label
    updateAppLabel("Initialized", "green")

    // Initialize schedule suspended
    updateSchedulingState()
  
    // Check setpoint schedule every minute and update devices when there's a time of day match
    schedule('0 0/1 * ? * *', checkSchedule)
}

def getSchedule() {
    // Set a default schedule
    if (!state.json) {
        state.json = '''{
            "08:00" : {"cooling": "72", "heating": "70", "dewpoint": "58", "humidity": "60", "fan_mode": "circ", "enabled": true, "valid": true},
            "12:00" : {"cooling": "73", "heating": "70", "dewpoint": "58", "humidity": "60", "fan_mode": "on", "enabled": true, "valid": true},
            "18:00" : {"cooling": "73", "heating": "70", "dewpoint": "58", "humidity": "60", "fan_mode": "on", "enabled": true, "valid": true},
            "22:00" : {"cooling": "70", "heating": "68", "dewpoint": "58", "humidity": "60", "fan_mode": "circ", "enabled": true, "valid": true}
        }'''
    }
}

String displayTable() {
    
    log.info "+++ displayTable: state.json=$state.json"

	String str = "<script src='https://code.iconify.design/iconify-icon/1.0.0/iconify-icon.min.js'></script>"
    
	str += "<style>.mdl-data-table tbody tr:hover{background-color:inherit} .tstat-col td,.tstat-col th { padding:4px 4px;text-align:center;font-size:12px} .tstat-col td {font-size:15px }" +
		"</style><div style='overflow-x:auto'><table class='mdl-data-table tstat-col' style=';border:2px solid black'>" +
		"<thead><tr style='border-bottom:2px solid black'><th style='border-right:2px solid black'>Enabled</th>" +
        "<th>Time</th>" +
        "<th>Cooling</th>" +
        "<th>Heating</th>" +
        "<th>Fan Mode</th>" +
        "<th>Dewpoint</th>" +
		"<th style='border-right:2px solid black'>Humidity</th>" +
        "<th style='border:none'></th>" +
        "<th style='border:none'></th>" +
        "<th style='border:none'></th>" +
        "</tr></thead>"
        
    Map records = new JsonSlurper().parseText(state.json)
    records.each { entry ->
        String tm = buttonLink("t$entry.key", "$entry.key", "purple")
        String del  = buttonLink("d$entry.key", "<iconify-icon icon='bx:trash'></iconify-icon>", "black", "20px")
        String copy = buttonLink("c$entry.key", "<iconify-icon icon='bx:copy'></iconify-icon>", "black", "20px")
        String edit = buttonLink("e$entry.key", "<iconify-icon icon='bx:edit'></iconify-icon>", "black", "20px") 
        String enabled = (entry.value["enabled"]) ? buttonLink("p$entry.key", "<iconify-icon icon='bx:check-square'></iconify-icon>", "green", "20px") : buttonLink("p$entry.key", "<iconify-icon icon='bx:square'></iconify-icon>", "red", "20px")
        String select = (entry.key == state.selection) ? buttonLink("s$entry.key", "<iconify-icon icon='bx:check-square'></iconify-icon>", "black", "20px") : buttonLink("s$entry.key", "<iconify-icon icon='bx:square'></iconify-icon>", "black", "20px")

            str += "<tr style='color:black'>" + 
                "<td title='Toggle record $entry.key' style='border-right:2px solid black'>$enabled</td>" +
                "<td style='color:purple'>$tm</td>" +
                "<td style='color:blue'>$entry.value.cooling</td>" +
                "<td style='color:red'>$entry.value.heating</td>" +
                "<td style='color:black'>$entry.value.fan_mode</td>" +
                "<td style='color:black'>$entry.value.dewpoint</td>" +
                "<td style='border-right:2px solid black'>$entry.value.humidity</td>" +
                "<td title='Edit record $entry.key' style='padding:0px 0px'>$edit</td>" +
                "<td title='Copy record $entry.key' style='padding:0px 2px 0px 2px'>$copy</td>" +
                "<td title='Delete record $entry.key' style='padding:0px 0px'>$del</td></tr>"
    }
	str += "</table></div>"
	str
}

String buttonLink(String btnName, String linkText, color = "#1A77C9", font = "15px") {
	"<div class='form-group'><input type='hidden' name='${btnName}.type' value='button'></div><div><div class='submitOnChange' onclick='buttonClick(this)' style='color:$color;cursor:pointer;font-size:$font'>$linkText</div></div><input type='hidden' name='settings[$btnName]' value=''>"
}

void appButtonHandler(btn) {
    
    if (btn.startsWith("s")) {
        state.selection = btn.minus("s")
        log.info "+++ appButtonHandler: startWith s: btn=$btn, state.selectAction=${state.selectAction}"
        state.selectAction = true     
    } else if (btn.startsWith("p")) {
        state.selection = btn.minus("p")
        state.enableAction = true
        log.info "+++ appButtonHandler: startWith p: btn=$btn, state.enableAction=${state.enableAction}"
    } else if (btn.startsWith("e")) {
        state.selection = btn.minus("e")
        state.editAction = true
        log.info "+++ appButtonHandler: startWith e: btn=$btn, state.editAction=${state.editAction}"
    } else if (btn.startsWith("c")) {
        state.selection = btn.minus("c")
        state.copyAction = true
        log.info "+++ appButtonHandler: startWith c: btn=$btn, state.copyAction=${state.copyAction}"
    } else if (btn.startsWith("d")) {
        state.selection = btn.minus("d")
        state.deleteAction = true
        log.info "+++ appButtonHandler: startWith d: btn=$btn, state.deleteAction=${state.deleteAction}"
    } else if (btn.startsWith("t")) {
        state.selection = btn.minus("t")
        state.editKeyAction = true
        log.info "+++ appButtonHandler: startWith t: btn=$btn, state.editKeyAction=${state.editKeyAction}"
     } else {
        log.info "+++ appButtonHandler: unused btn=$btn"
    }
    log.info "+++ appButtonHandler: state.selection=${state.selection}"
}  

def addMinute(str) {
    def hr = str.substring(0,2)
    def mn = str.substring(3)
    def imin = mn.toInteger()
    imin += 1
    minplus = String.format("%02d", imin)
    return "${hr}:${minplus}"
}

def validTime(str) {
    def result = true
    try {
        Date.parse('HH:mm', str)
    } catch (Exception ex) {
        return false
    }
}

def controllerDeviceHandler(evt)
{
    debuglog "+++ controllerDeviceHandler: value changed: ${evt.name}:${evt.value}"
    switch(evt.name)
    {
        case "switch":
            state.controlling = evt.value
            break
        case "thermostatMode":
            state.controllerThermostatMode = evt.value
            break
        case "thermostatSetpoint":
            state.controllerTempSetpoint = evt.value
            break
        case "coolingSetpoint":
            state.controllerCoolingSetpoint = evt.value
            break
        case "heatingSetpoint":
            state.controllerHeatingSetpoint = evt.value
            break
    }
}

def tstatDeviceHandler(evt)
{
    debuglog "+++ tstatDeviceHandler: value changed: ${evt.name}:${evt.value}"
    switch(evt.name)
    {
        case "thermostatMode":
            state.tstatThermostatMode = evt.value
            break
        case "thermostatSetpoint":
            state.tstatTempSetpoint = evt.value
            break
        case "coolingSetpoint":
            state.tstatCoolingSetpoint = evt.value
            break
        case "heatingSetpoint":
            state.tstatHeatingSetpoint = evt.value
            break
    }
}

def fanModeVariableHandler(evt) 
{
    infolog "+++ fanModeVariableHandler: Variable changed: ${evt.value}"
    state.fanModeRequested = evt.value
    
	switch(evt.value)
	{
		case "on":
			break
        case "auto":
			break
        case "circ":
			break
    }    
}

def outdoorTempHandler(evt)
{
    infolog "+++ outdoorTempHandler: value changed: ${evt.value}"

    state.outdoorTemp = evt.value.toBigDecimal()
    def currentTempBias = state.tempBias
    
    // Compute new bias and adjust current setpoints
    state.tempBias = calcTempBias(state.outdoorTemp)
    if (currentTempBias != state.tempBias) updateCoolingSetpoint()
}

def calcTempBias(temp) 
{    
    debuglog "+++ calcTempBias: temp: ${temp}"    
    def bias = 0.0
    
    if (state.suspendTempBias) {
        bias = 0.0
    } else if (temp > 96.0) {
        bias = 3.0
    } else if (temp > 93.0) {
        bias = 2.0
    } else if (temp > 90.0) {
        bias = 1.0
    }
    return bias
}

def suspendTempBiasSwitchHandler(evt) 
{
    infolog "+++ suspendTempBiasSwitchHandler: Switch changed: ${evt.value}"
    
	switch(evt.value)
	{
		case "on":
            state.suspendTempBias = true
			break
        case "off":
            state.suspendTempBias = false
			break
    }
}

def suspendSchedulingSwitchHandler(evt) 
{
    infolog "+++ suspendSchedulingSwitchHandler: Switch changed: ${evt.value}"
    updateSchedulingState()
}

def updateSchedulingState()
{
    def suspend = false
    suspendSchedulingSwitches.each {
        suspend = suspend || (it.currentValue("switch") == "on" ? true : false)
    }

    state.scheduling = !suspend
    debuglog "+++ updateSchedulingState: state.scheduling = ${state.scheduling}"
}

def checkSchedule() 
{    
    debuglog "+++ checkSchedule: "
    debuglog "+++ checkSchedule: scheduling=${state.scheduling}"

    // Punt if scheduling is suspended
    if (!state.scheduling) {
        updateAppLabel("Suspended", "red")
        return
    }
    updateAppLabel("Active", "green")
    
    // Create a HH:mm string for the current time
    time_now = now()
    def date = new Date()
    Calendar calendar = Calendar.getInstance()
    calendar.setTime(date)
    int hour = calendar.get(Calendar.HOUR_OF_DAY)
    int minute = calendar.get(Calendar.MINUTE)
    def tod = String.format("%02d", hour) + ':' + String.format("%02d", minute)
    debuglog "+++ checkSchedule: date=${date}, hour=${hour}, minute=${minute}, tod=${tod}"
    
    // Create the setpoint table, find a matching HH:mm. If !enabled || invalid, don't update output devices
    def table = new JsonSlurper().parseText(state.json)     

    table.each { entry ->
        if (entry.key == tod) {
            infolog "+++ checkSchedule: time of day match found"
            
            // Save the schedule entries
            state.scheduledCooling = entry.value.cooling.toInteger()
            state.scheduledHeating = entry.value.heating.toInteger()
            state.scheduledDewpoint = entry.value.dewpoint.toInteger()
            state.scheduledHumidity = entry.value.humidity.toInteger()
            state.scheduledFanMode = entry.value.fan_mode
            state.scheduledEnabled = entry.value.enabled
            state.scheduledValid = entry.value.valid
            
            // Invoke setpoint changes
            if (entry.value.enabled && entry.value.valid) {
                updateCoolingSetpoint()
                updateHeatingSetpoint()
                updateDewpointSetpoint()
                updateHumiditySetpoint()
                updateFanMode()
                debuglog "+++ checkSchedule: record enabled and valid, setpoints updated"
            } else {
                debuglog "+++ checkSchedule: record disabled or invalid, setpoints not updated"
            }
        }
    }
}

def updateCoolingSetpoint() {

    // If in cool mode, adjust thermostat cooling scheduled setpoint wrt outdoor temperature
    if (state.controllerThermostatMode == "cool") {
        def spt = state.scheduledCooling + state.tempBias
        controllerDevice.setCoolingSetpoint(spt)
        
        def lbl = controllerDevice.getLabel()
        infolog "+++ updateCoolingSetpoint: ${lbl} cooling to ${spt}"
    }
}

def updateHeatingSetpoint() {
    
    // If in heat mode, send thermostat heating scheduled setpoint
    if (state.controllerThermostatMode == "heat") {
        def spt = state.scheduledHeating
        controllerDevice.setHeatingSetpoint(spt)
    
        def lbl = controllerDevice.getLabel()
        infolog "+++ updateHeatingSetpoint: ${lbl} heating to ${spt}"
    }
}

def updateDewpointSetpoint() {

    def spt = state.scheduledDewpoint
    dewpointDevice.setTemperature(spt)
    
    def lbl = dewpointDevice.getLabel()
    infolog "+++ updateDewpointSetpoint: ${lbl} set to ${spt}"
}

def updateHumiditySetpoint() {

    def spt = state.scheduledHumidity
    humidityDevice.setHumidity(spt)
    
    def lbl = humidityDevice.getLabel()
    infolog "+++ updateHumiditySetpoint: ${lbl} set to ${spt}"
}

def updateFanMode() {

    def mode = state.scheduledFanMode
    
    // Let the Fan Mode Controller do the work - set the connector variable to the fan mode
    fanModeVariable.setVariable(mode)
   
    def lbl = tstatDevice.getLabel()
    infolog "+++ updateFanMode: ${lbl} set to ${mode}"
}

//
// Utility functions
//
def updateAppLabel(textStr, textColor, def textPrefix=null) {
    //def str = """<span style='color:$textColor'> ($textPrefix $textStr)</span>"""
    def str = (textPrefix != null) ? """<span style='color:$textColor'> ($textPrefix $textStr)</span>""" : """<span style='color:$textColor'> ($textStr)</span>"""
    app.updateLabel(customLabel + str)
}

def debuglog(statement)
{   
	def logL = 0
    if (logLevel) logL = logLevel.toInteger()
    if (logL == 0) {return}//bail
    else if (logL >= 2)
	{
		log.debug(statement)
	}
}

def infolog(statement)
{       
	def logL = 0
    if (logLevel) logL = logLevel.toInteger()
    if (logL == 0) {return}//bail
    else if (logL >= 1)
	{
		log.info(statement)
	}
}
