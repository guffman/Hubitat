/**
 *  ****************  Ceiling Fan Controller  ****************
 *
 *  Usage:
 *  This was designed to control a ceiling fan using a Virtual Fan Controller device synched to a Smart Dimmer device.
 *
**/

definition (
    name: "Ceiling Fan Controller",
    namespace: "Guffman",
    author: "Guffman",
    description: "Controller for a ceiling fan. Uses a Virtual Fan Controller and a Smart Dimmer.",
    category: "My Apps",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
     page name: "mainPage", title: "", install: true, uninstall: true
}

def mainPage() {

    dynamicPage(name: "mainPage") {

        section("<b>Virtual Fan Controller</b>") {

            input (
              name: "fanController1", 
              type: "capability.fanControl", 
              title: "Select Virtual Fan Controller Device", 
              required: true, 
              multiple: false, 
              submitOnChange: true
            )

            if (fanController1) {
                input (
                    name: "trackDimmer", 
                    type: "bool", 
                    title: "Track physical dimmer changes", 
                    required: true, 
                    defaultValue: "true"
                )
            } 
        }

        section("<b>Fan Dimmer</b>") {
            input (
                name: "fanDimmer1", 
                type: "capability.switchLevel", 
                title: "Select Dimmer Wired to Ceiling Fan", 
                required: true, 
                multiple: false
            )
        }

        section("") {
            input (
                name: "debugMode", 
                type: "bool", 
                title: "Enable logging", 
                required: true, 
                defaultValue: false
            )
  	    }
    }
}

def installed() {
    initialize()
}

def updated() {
    initialize()
}

def initialize() {

    subscribe(fanController1, "speed", fanControllerHandler)
    subscribe(fanDimmer1, "level", fanDimmerLevelHandler)
    subscribe(fanDimmer1, "switch", fanDimmerSwitchHandler)

    state.fanSpeedRequest = fanController1.speed
    state.fanDimmerLevel = fanDimmer1.level
    state.fanDimmerState = fanDimmer1.switch
}

def fanControllerHandler(evt) {
    
    state.fanSpeedRequest = evt.value
    logDebug("Fan Controller Event = $state.fanSpeedRequest")

    switch (state.fanSpeedRequest)
    {
        case "off":
            fanOff()
            break;
        case "on":
            fanOn()
            break;
        case "auto":
            break;
        case "low":
            fanSetLevel(24)
            break;
        case "medium-low":
            fanSetLevel(24)
            break;
        case "medium":
            fanSetLevel(49)
            break;
        case "medium-high":
            fanSetLevel(74)
            break;
        case "high":
            fanSetLevel(99)
            break;
    }

}

def fanDimmerLevelHandler(evt) {

    state.fanDimmerLevel = evt.value
    logDebug("Fan Dimmer Level Event = $state.fanDimmerLevel")
    def lvl = evt.value.toInteger()

    switch (lvl)
    {
        case 1..24:
            fanSetSpeed("low")
            logDebug("Tracking dimmer level - fan speed set to low")
            break;
        case 25..49:
            fanSetSpeed("medium")
            logDebug("Tracking dimmer level - fan speed set to medium")
            break;
        case 50..74:
            fanSetSpeed("medium-high")
            logDebug("Tracking dimmer level - fan speed set to medium-high")
            break;
        case 75..99:
            fanSetSpeed("high")
            logDebug("Tracking dimmer level - fan speed set to high")
            break;
    }
}

def fanDimmerSwitchHandler(evt) {
    state.fanDimmerState = evt.value
    logDebug("Fan Dimmer Switch Event = $state.fanDimmerState")

    switch (state.fanDimmerState)
    {
        case "on":
            fanSetSpeed("on")
            break;
        case "off":
            fanSetSpeed("off")
            break;
    }
}

def fanOn(){
   fanDimmer1.on()
}

def fanOff(){
    fanDimmer1.off()
}

def fanSetLevel(val){
    fanDimmer1.setLevel(val)
}

def fanSetSpeed(val){
    fanController1.setSpeed(val)
}

def logDebug(txt){
    try {
    	if (settings.debugMode) { log.debug("${app.label} - ${txt}") }
    } catch(ex) {
    	log.error("bad debug message")
    }
}
