/*
*  HVAC Fan Circulate Scheduler
*
*    Simulates "circulate" fan mode if not supported directly by the thermostat.
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
*/

definition(
    name: "HVAC Fan Circulate Scheduler",
    namespace: "Guffman",
    author: "Guffman",
    description: "Simulates the 'circ' fan mode for thermostats using the 'on' and 'auto' fan modes.",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/guffman/Hubitat/master/apps/HVACFanCirculateScheduler.groovy"
)

preferences {
    page(name: "pageConfig") // Doing it this way eliminates the default app name/mode options.
}

def pageConfig() {
    dynamicPage(name: "", title: "", install: true, uninstall: true, refreshInterval:0) {
        section("<b>General</b>") 
        {
		input (name: "customLabel", type: "text", title: "Enter a name for the app (optional)", required: false, defaultValue: "HVAC Fan Circulate Controller")
		}
	    section("<b>Scheduler Input/Output Devices</b>")
		{
            input "tstatDevice", "capability.thermostat", title: "Thermostat:", required: true, multiple: false
            input "dehumidifierSwitch", "capability.switch", title: "Dehumidifer Running Switch:", required: true, multiple: false
            input "fanCircModeSwitch", "capability.switch", title: "Fan Circulate Mode Switch:", required: true, multiple: false
        }
		section("<b>Settings</b>")
		{   
            input("circOnTime", "enum", title: "Fan On Time (mins)", multiple: false, required: true, options: [["6":"6"],["8":"8"],["10":"10"]], defaultValue: "6")  
			input("circOffTime", "enum", title: "Fan Idle Time (mins)", multiple: false, required: true, options: [["6":"6"],["8":"8"],["10":"10"],["15":"15"],["20":"20"]], defaultValue: "10")  
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
    
    // Thermostat fan circulate mode request (on/off)
    state.fanCircModeRequested = (fanCircModeSwitch.currentValue("switch") == "on") ? true : false
    subscribe(fanCircModeSwitch, "switch", fanCircModeSwitchHandler)
         
    // Dehumidifer state (on/off)
    state.dehumidifierRunning = (dehumidifierSwitch.currentValue("switch") == "on") ? true : false
    subscribe(dehumidifierSwitch, "switch", dehumidifierSwitchHandler)
         
    // Set app label
    state.fanCircModeRequested ? updateAppLabel("Circulation Mode On", "green") : updateAppLabel("Circulation Mode Off", "red")
    
    // Initalize the circulation cycle. 
    state.cycleOn = false
    cycleOn()
}

def tstatDeviceHandler(evt)
{
    debuglog "+++ tstatDeviceHandler: value changed: ${evt.name}:${evt.value}"
    switch(evt.name)
    {
        case "thermostatMode":
            state.tstatThermostatMode = evt.value
            break
        case "thermostatOperatingState":
            state.tstatOperatingState = evt.value
            // When operating state changes, idle state should start a circ cycle
            cycleOn()
            break
        case "thermostatFanMode":
            state.tstatFanMode = evt.value
            break
        case "thermostatFanState":
            state.tstatFanState = evt.value
            break
    }
}

def fanCircModeSwitchHandler(evt) 
{
    infolog "+++ fanCircModeSwitchHandler: Switch changed: ${evt.value}"
    
	switch(evt.value)
	{
		case "on":
            state.fanCircModeRequested = true
            // Initalize the circulation cycle. 
            cycleOn()
            updateAppLabel("Circulate Mode On", "green")
			break
        case "off":
            state.fanCircModeRequested = false
            updateAppLabel("Circulate Mode Off", "red")
			break
    }    
}

def dehumidifierSwitchHandler(evt) 
{
    infolog "+++ dehumidifierSwitchHandler: Switch changed: ${evt.value}"
     
	switch(evt.value)
	{
		case "on":
            state.dehumidifierRunning = true
            // When dehumidifer turns on, set fan to on.
            setFanMode('on')
			break
        case "off":
            state.dehumidifierRunning = false
            // When dehumidifer turns off, if circ cycle is idle, set fan to auto.
            if (!state.cycleOn) setFanMode('auto')
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

def cycleOn() 
{
    // Continue the circ cycle, if circ mode requested and the fan is idle and not in an on cycle
    if (state.fanCircModeRequested)
    {
        if (isIdle()) 
        {
            state.cycleOn = true
            setFanMode('on')
            def delay = Long.valueOf(circOnTime) * 60
            runIn(delay, cycleWait)
            infolog "+++ cycleOn: schedule cycleWait() in ${delay} seconds"
        } else 
        {
            infolog "+++ cycleOn: skipped, fan already on"
        }
    } else
    {
        infolog "+++ cycleOn: Circ mode off"
    }
}

def cycleWait()
{
    // Continue the circ cycle if circ mode requested and currently in a cycle
    if (state.fanCircModeRequested) 
    {
        if (state.cycleOn) 
        {
            state.cycleOn = false
            // Check if dehumidifier running, if so keep fan in run mode.
            state.dehumidifierRunning ? {infolog "+++ cycleWait: dehumidifer running"} : setFanMode('auto')
            def delay = Long.valueOf(circOffTime) * 60
            runIn(delay, cycleOn)
            infolog "+++ cycleWait: schedule cycleOn() in ${delay} seconds"
        } else
        {
            infolog "+++ cycleWait: skipped, already waiting"
        }
    } else
    {
        infolog "+++ cycleWait: Circ mode off"
    }
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
    debuglog "+++ updateFanMode: ${lbl} set to ${mode}"
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
