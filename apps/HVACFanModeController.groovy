/*
*  HVAC Fan Mode Controller
*
*    Controls auto/on/circ modes for a thermostat. Simulates "circulate" fan mode if not supported directly by the thermostat.
*    Override fan mode if dehumidifier running to improve air mixing.
*
*  Copyright 2023 Guffman Ventures LLC
*  GNU General Public License v2 (https://www.gnu.org/licenses/gpl-2.0.txt)
*
*  Change History:
*
*    Date        Who            What
*    ----        ---            ----
*    2023-10-18  Marc Chevis    Original Creation
*    2023-10-24  Marc Chevis    Changed fan mode command from digital to string variable (on/auto/circ).
*/

definition(
    name: "HVAC Fan Mode Controller",
    namespace: "Guffman",
    author: "Guffman",
    description: "Controls a thermostat fan. Simulates 'circ' fan mode for thermostats using the 'on' and 'auto' fan modes.",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/guffman/Hubitat/master/apps/HVACFanModeController.groovy"
)

preferences {
    page(name: "pageConfig") // Doing it this way eliminates the default app name/mode options.
}

def pageConfig() {
    
    dynamicPage(name: "", title: "", install: true, uninstall: true, refreshInterval:0)
    {
        section("<b>General</b>") 
        {
            input (name: "customLabel", type: "text", title: "Enter a name for the app (optional)", required: false, defaultValue: "HVAC Fan Mode Controller", submitOnChange: true)
            if(customLabel) app.updateLabel("$customLabel")
        }
	    section("<b>Scheduler Input/Output Devices</b>")
		{
            input "tstatDevice", "capability.thermostat", title: "Thermostat:", required: true, multiple: false
            input "fanModeVariable", "capability.variable", title: "Requested Fan Mode - Thermostat Scheduler State Variable:", required: true, multiple: false
        }    
		section("<b>Settings</b>")
		{   
            input("circOnTime", "enum", title: "Fan On Time (mins)", multiple: false, required: true, options: [["3":"3"],["6":"6"],["8":"8"],["10":"10"]], defaultValue: "6")  
			input("circOffTime", "enum", title: "Fan Idle Time (mins)", multiple: false, required: true, options: [["3":"3"],["6":"6"],["8":"8"],["10":"10"],["15":"15"],["20":"20"]], defaultValue: "10")
		}
        section("<b>Options</b>")
        {
            input "dehumOverride", "bool", title: "Override fan mode when Dehumidifer running?", required: true, defaultValue: false, submitOnChange: true
            if (dehumOverride) input "dehumidifierSwitch", "capability.switch", title: "Dehumidifer Running Switch:", required: true, multiple: false
            
            input "fanCircEcho", "bool", title: "Track circulate mode?", required: true, defaultValue: false, submitOnChange: true
            if (fanCircEcho) input "fanCircModeSwitch", "capability.switch", title: "Fan Circulate Mode tracking switch:", required: true, multiple: false
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
            
    // Thermostat operating state
    state.tstatOperatingState = tstatDevice.currentValue("thermostatOperatingState")
    subscribe(tstatDevice, "thermostatOperatingState", tstatDeviceHandler)
    
    // Thermostat fan mode (on/auto)
    state.tstatFanMode = tstatDevice.currentValue("thermostatFanMode")
    subscribe(tstatDevice, "thermostatFanMode", tstatDeviceHandler)
    
    // Thermostat fan state (running/idle)
    state.tstatFanState = tstatDevice.currentValue("thermostatFanState")
    subscribe(tstatDevice, "thermostatFanState", tstatDeviceHandler)
    
    // Thermostat Scheduler requested state (auto/on/circ)
    state.fanModeScheduled = fanModeVariable.currentValue("variable")
    subscribe(fanModeVariable, "variable", fanModeVariableHandler)
         
    // Dehumidifer state mode override option
    if (dehumOverride) 
    {
        state.dehumidifierRunning = (dehumidifierSwitch.currentValue("switch") == "on") ? true : false
        subscribe(dehumidifierSwitch, "switch", dehumidifierSwitchHandler)
    }
         
    // Set app label
    def uc = state.fanModeScheduled.capitalize()
    updateAppLabel("Fan Mode ${uc}", "green")
    debuglog "+++ Initializing: Scheduled fan mode is $state.fanModeScheduled"

    // Check conditions every minute and update thermostat fan mode accordingly
    schedule('5 0/1 * ? * *', crunchLogic)
}

def tstatDeviceHandler(evt)
{
    infolog "+++ tstatDeviceHandler: value changed: ${evt.name}:${evt.value}"
    switch(evt.name)
    {
        case "thermostatMode":
            state.tstatThermostatMode = evt.value
            break
        case "thermostatOperatingState":
            state.tstatOperatingState = evt.value
            break
        case "thermostatFanMode":
            state.tstatFanMode = evt.value
            break
        case "thermostatFanState":
            state.tstatFanState = evt.value
            break
    }
}

def fanModeVariableHandler(evt) 
{
    infolog "+++ fanModeVariableHandler: Variable changed: ${evt.value}"
    state.fanModeScheduled = evt.value
    
	switch(evt.value)
	{
		case "on":
            updateAppLabel("Fan Mode On", "green")
			break
        case "auto":
            updateAppLabel("Fan Mode Auto", "green")
			break
        case "circ":
            updateAppLabel("Fan Mode Circ", "green")
			break
    }
    
    if (fanCircEcho)
    {
        def fc = state.fanModeScheduled == 'circ' ? true : false
        setFanCircModeSwitch(fc)
        infolog "+++ fanModeVariableHandler: Set $fanCircModeSwitch to $fc"
    }

}

def dehumidifierSwitchHandler(evt) 
{
    infolog "+++ dehumidifierSwitchHandler: Switch changed: ${evt.value}"
    
    // Code only executed if dehumidifer override is true
	switch(evt.value)
	{
		case "on":
            state.dehumidifierRunning = true
			break
        case "off":
            state.dehumidifierRunning = false
			break
    }
}

def isIdle()
{
    def idle = true
    
    switch(state.tstatOperatingState)
    {
        case "heating":
            idle = false
            break
        case "cooling":
            idle = false
            break
        case "pending heat":
            idle = false
            break
        case "pending cool":
            idle = false
            break
    }
    debuglog "+++ isIdle: ${idle}" 
    
    return idle
}

def crunchLogic() {
    
    // Logic table:
    // Dehumidifer on  : fanOn
    // Dehumidifer off && fanModeScheduled = on ? setFanMode = on
    // Dehumidifer off && fanModeScheduled = auto ? setFanMode = auto
    // Dehumidifer off && fanModeScheduled = circ && !isIdle ? setFanMode = auto
    // Dehumidifer off && fanModeScheduled = circ && isIdle && fanState = idle ? setFanMode = on for cycleActive time
    // Dehumidifer off && fanModeScheduled = circ && isIdle && fanState = running ? setFanMode = auto for cycleWait time
    
    def idle = isIdle()
    
    if (dehumOverride && state.dehumidifierRunning)
    {
        debuglog "+++ crunchLogic: dehumidifer running"
        setFanMode('on')
        resetCircTimers()
        return
    }
    
    if (state.fanModeScheduled == 'on')
    {
        debuglog "+++ crunchLogic: scheduled fan mode = on"
        setFanMode('on')
        resetCircTimers()
        return
    }
    
    if (state.fanModeScheduled == 'auto')
    {
        debuglog "+++ crunchLogic: scheduled fan mode = auto"
        setFanMode('auto')
        resetCircTimers()
        return
    }
     
    if (state.fanModeScheduled == 'circ' && !idle)
    {
        debuglog "+++ crunchLogic: scheduled fan mode = circ, thermostat heating or cooling"
        setFanMode('auto')
        resetCircTimers()
        return
    } 
        
    if (state.fanModeScheduled == 'circ' && idle && state.circActive)
    {
        state.circActiveCounter += 1
        debuglog "+++ crunchLogic: scheduled fan mode = circ, thermostat idle, circActive=true, circActiveCounter=${state.circActiveCounter}"
        if (state.circActiveCounter < circOnTime.toInteger())
        {
            setFanMode('on')
        } else
        {
            debuglog "+++ crunchLogic: scheduled fan mode = circ, thermostat idle, circActive timed out}"
            setFanMode('auto')
            state.circActive = false
            resetCircTimers()
        }        
        return
    } 
    
    if (state.fanModeScheduled == 'circ' && idle && !state.circActive)
    {
        state.circWaitingCounter += 1
        debuglog "+++ crunchLogic: scheduled fan mode = circ, thermostat idle, circActive=false, circWaitingCounter=${state.circWaitingCounter}"
        if (state.circWaitingCounter < circOffTime.toInteger()) {
            setFanMode('auto')
        } else
        {
            debuglog "+++ crunchLogic: scheduled fan mode = circ, thermostat idle, circWaiting timed out}"
            setFanMode('on')
            state.circActive = true
            resetCircTimers()
        }
        return
    } 
}

def resetCircTimers()
{
    debuglog "+++ resetCircTimers: "
    state.circActiveCounter = 0
    state.circWaitingCounter = 0
}

def setFanMode(mode) 
{
    switch(mode)
	{
		case "on":
            tstatDevice.fanOn()
			break
        case "auto":
            tstatDevice.fanAuto()
			break
    }
    
    def lbl = tstatDevice.getLabel()
    debuglog "+++ setFanMode: ${lbl} set to ${mode}"
}

def setFanCircModeSwitch (sw) 
{
    if (fanCircModeSwitch) {sw ? fanCircModeSwitch.on() : fanCircModeSwitch.off()}
    
    def lbl = tstatDevice.getLabel()
    debuglog "+++ setFanCircModeSwitch: ${lbl} set to ${sw}"
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
