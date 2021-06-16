/* Copyright 2021 Guffman Ventures LLC  All Rights Reserved
 *
 * Virtual Thermal Comfort Sensor - a device driver that receives a calculated comfort value from the node-red
 * node-red-contrib-comfort node. Based on ASHRAE Standard 55.
 *
 * Initial Version - 6-16-2021
 *
 *
 *
 */

metadata {
    definition (name: "Virtual Thermal Comfort Sensor", namespace: "guffman", author: "Guffman") {
        capability "Sensor"
       
        command "setComfort", [[type: "Number", descriptionText: "An index between -3.0 and 3.0", range: "-3.0..3.0"]]
  
        attribute "comfort", "Number"
        attribute "sensation", "String"
    }
    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}

def logsOff(){
    log.warn "debug logging disabled..."
    device.updateSetting("logEnable",[value:"false",type:"bool"])
}

def installed() {
    log.warn "installed..."
    setComfort(0)

    runIn(1800,logsOff)
}

def updated() {
    log.info "updated..."
    log.warn "debug logging is: ${logEnable == true}"
    log.warn "description logging is: ${txtEnable == true}"
    if (logEnable) runIn(1800,logsOff)
}

def setComfort(val) {
    def descriptionText = "${device.displayName} Comfort Index is ${val}"
    if (txtEnable) log.info "${descriptionText}"
    sendEvent(name: "comfort", value: val, descriptionText: descriptionText)
    
    // Lookup string equivalent
    def dval = val.toDouble()
    if (dval < -2.5) {
        txt = "Cold"
    } else if (dval < -1.5) {
        txt = "Cool"
    } else if (dval < -0.5) {
        txt = "Slightly Cool"
    } else if (dval < 0.5) {
        txt = "Neutral"
    } else if (dval < 1.5) {
        txt = "Slightly Warm"
    } else if (dval < 2.5) {
        txt = "Warm"
    } else {
        txt = "Hot"
    }
    
    descriptionText = "${device.displayName} Sensation is ${txt}"
    if (txtEnable) log.info "${descriptionText}"
    sendEvent(name: "sensation", value: txt, descriptionText: descriptionText)
}
