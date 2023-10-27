import groovy.transform.Field
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.text.SimpleDateFormat
import java.net.URLEncoder

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
*/

definition(
    name: "HVAC Setpoint Scheduler",
    namespace: "Guffman",
    author: "Guffman",
    description: "Adjusts HVAC setpoints based on time of day and outdoor temperature.",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/guffman/Hubitat/master/apps/HVACSetpointScheduler.groovy"
)

preferences {
    page(name: "pageConfig") // Doing it this way eliminates the default app name/mode options.
}

def pageConfig() {
    dynamicPage(name: "", title: "", install: true, uninstall: true, refreshInterval:0) {
        section("<b>General</b>") 
        {
		input (name: "customLabel", type: "text", title: "Enter a name for the app (optional)", required: false, defaultValue: "HVAC Setpoint Scheduler", submitOnChange: true)
        if(customLabel) app.updateLabel("$customLabel")
        }
	    section("<b>Device Configuration</b>")
		{ 
            input "tstatDevice", "capability.thermostat", title: "Physical Thermostat:", required: true, multiple: false
            input "controllerDevice", "capability.thermostat", title: "Controller Thermostat:", required: true, multiple: false
            input "fanModeVariable", "capability.variable", title: "Requested Fan Mode State Variable:", required: true, multiple: false
            input "dewpointDevice", "capability.temperatureMeasurement", title: "Dewpoint Setpoint:", required: true, multiple: false
            input "humidityDevice", "capability.relativeHumidityMeasurement", title: "Humidity Setpoint:", required: false, multiple: false
			input "outdoorTempDevice", "capability.temperatureMeasurement", title: "Outdoor Temperature Sensor:", required: true, multiple: false
            input "suspendTempBiasSwitch", "capability.switch", title: "Suspend Outdoor Temperature Bias Switch:", required: false, multiple: false
			input "vacationModeSwitch", "capability.switch", title: "Vacation Mode Switch:", required: false, multiple: false
        }
        section("<b>Scheduler Configuration</b>")
        {
            input("setpointFile", "string", title: "Setpoint Schedule Filename:", multiple: false, required: true, submitOnChange: true) 
            if (setpointFile != null) {
                fstr = readFile setpointFile
                // Text box editor
                input "table", "textarea", title: "File Contents", width: 8, rows: 10, defaultValue: fstr
            } 
        }
		section("<b>Logging</b>")
		{                       
			input("logLevel", "enum", title: "Logging level", multiple: false, required: true, options: [["0":"None"],["1":"Info"],["2":"Debug"]], defaultValue: "0")  
		}
    }
}

def installed()
{
	initialize()
}

def updated()
{
	unsubscribe()
    unschedule()
	initialize()
}

def initialize()
{
    infolog "+++ Initializing +++"
    
    //fstr = readFile setpointFile
    Map setpoints = new JsonSlurper().parseText(table)
    //infolog "+++ Setpoint filename: ${setpointFile}:"
    setpoints.each { entry ->
        infolog "+++ Setpoint record: ${entry.key} : ${entry.value}"
    }
    
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

    // Scheduling suspended while in vacation mode
    if (vacationModeSwitch != null) {
        state.scheduling = (vacationModeSwitch.currentValue("switch") == "on") ? false : true
        subscribe(vacationModeSwitch, "switch", vacationModeSwitchHandler)
    } else {
        state.scheduling = true
    }
     
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
    
    // Check setpoint table every minute and update devices when there's a time of day match
    schedule('0 0/1 * ? * *', checkSchedule, [data: setpoints])
}

String readFile(fName){
    
    uri = "http://${location.hub.localIP}:8080/local/${fName}"


    def params = [
        uri: uri,
        contentType: "text/html",
        textParser: true,
        headers: [
				"Cookie": cookie,
                "Accept": "application/octet-stream"
            ]
    ]

    try {
        httpGet(params) { resp ->
            if(resp!= null) {       
               int i = 0
               String delim = ""
               i = resp.data.read() 
               while (i != -1){
                   char c = (char) i
                   delim+=c
                   i = resp.data.read() 
               }
                debuglog "File Read Data - filename: ${fname}: $delim"
               return delim
            }
            else {
                log.error "Null Response"
            }
        }
    } catch (exception) {
        log.error "Read Error: ${exception.message}"
        return null;
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
    debuglog "+++ outdoorTempHandler: value changed: ${evt.value}"

    state.outdoorTemp = evt.value.toBigDecimal()
    
    // Compute new bias and adjust current setpoints
    state.tempBias = calcTempBias(state.outdoorTemp)
    if (state.tempBias > 0) updateCoolingSetpoint()
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
    debuglog "+++ suspendTempBiasSwitchHandler: Switch changed: ${evt.value}"
    
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

def vacationModeSwitchHandler(evt) 
{
    debuglog "+++ vacationModeSwitchHandler: Switch changed: ${evt.value}"
    
	switch(evt.value)
	{
		case "on":
            state.scheduling = false
			break
        case "off":
            state.scheduling = true
			break
    }
}

def checkSchedule(setpoints) 
{    
    debuglog "+++ checkSchedule: "
    debuglog "+++ checkSchedule: scheduling=${state.scheduling}"

    // Punt if scheduling is disabled
    if (!state.scheduling) {
        updateAppLabel("Disabled", "red")
        return
    }
    
    // Lookup setpoints for the current time interval
    time_now = now()
    def date = new Date()
    Calendar calendar = Calendar.getInstance()
    calendar.setTime(date)
    int hour = calendar.get(Calendar.HOUR_OF_DAY);  
    int minute = calendar.get(Calendar.MINUTE);  
    int seconds = calendar.get(Calendar.SECOND); 
    debuglog "+++ checkSchedule: date=${date}, hour=${hour}, minute=${minute}, seconds=${seconds}"

    setpoints.each { entry ->
        if (hour == entry.value.hour.toInteger() && minute == entry.value.minute.toInteger()) {
            infolog "+++ checkSchedule: time of day match found, hour: ${entry.value.hour}, minute: ${entry.value.minute}"
            
            // Save the schedule entries
            state.scheduledCooling = entry.value.cooling.toInteger()
            state.scheduledHeating = entry.value.heating.toInteger()
            state.scheduledDewpoint = entry.value.dewpoint.toInteger()
            state.scheduledHumidity = entry.value.humidity.toInteger()
            state.scheduledFanMode = entry.value.fan_mode
            
            // Invoke setpoint changes
            updateCoolingSetpoint()
            updateHeatingSetpoint()
            updateDewpointSetpoint()
            updateHumiditySetpoint()
            updateFanMode()
        }
    }
}

def updateCoolingSetpoint() {

    // If in cool mode, adjust thermostat cooling scheduled setpoint wrt outdoor temperature
    if (state.controllerThermostatMode == "cool") {
        def spt = state.scheduledCooling + state.tempBias
        controllerDevice.setCoolingSetpoint(spt)
        
        def lbl = controllerDevice.getLabel()
        def str = state.scheduling ? "Enabled, Cooling to ${spt}" : "Disabled"
        def color = state.scheduling ? "green" : "red"
        updateAppLabel(str, color) 
        infolog "+++ updateCoolingSetpoint: ${lbl} cooling to ${spt}"
    }
}

def updateHeatingSetpoint() {
    
    // If in heat mode, send thermostat heating scheduled setpoint
    if (state.controllerThermostatMode == "heat") {
        def spt = state.scheduledHeating
        controllerDevice.setHeatingSetpoint(spt)
    
        def lbl = controllerDevice.getLabel()
        def str = state.scheduling ? "Enabled, Heating to ${spt}" : "Disabled"
        def color = state.scheduling ? "green" : "red"
        updateAppLabel(str, color) 
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
