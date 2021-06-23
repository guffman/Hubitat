/**
 *  Virtual Stopwatch Device
 *
 *  After a Start and a subsequent Stop command, calculate the elapsed time using the chosen time units, then send a "session" event
 *  with the total elapsed time, and a data map of the starting and ending timestamps. The Stop command triggers the session event.
 *  Periodic elapsed time events are issued using a dedicated timeSinceStarted attribute. 
 *
 *  Button capabilities are added for convenience:
 *      Button 1 pushed - starts the stopwatch
 *      Button 2 pushed - stops the stopwatch
 *      Button 3 pushed - pauses the running stopwatch
 *      Button 4 pushed - cancels the running stopwatch
 *
 *  Pause is treated as a suspension of elapsed time; while paused there is no additive contribution to elapsed time. After a subsequent
 *  start command is issued, the stopwatch resumes accruing elapsed time. After a stop command is issued following a pause, the session is
 *  ended normally, with the accrued elapsed time updated.
 *
 *
 *  https://raw.githubusercontent.com/guffman/Hubitat/master/Drivers/stopwatch-device.src/stopwatch-device.groovy
 *
 *  Copyright 2021 Guffman Ventures LLC
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
 *    Date        Who            What
 *    ----        ---            ----
 *    2021-06-08  Marc Chevis    Original
 *    2021-06-12  Marc Chevis    Added attribute updates while timer is running, button capability
 *    2021-06-23  Marc Chevis    Added some statistics attributes
 * 
 */

metadata {
    definition (name: "Virtual Stopwatch Device", namespace: "guffman", author: "Guffman", importUrl: "") {
        capability "PushableButton"
        capability "TimedSession"
        
        // These attributes are used for each stopwatch timed event occurence
        attribute "elapsedTime", "number"
        attribute "clockTime", "number"
        attribute "units", "string"
        
        // These attributes are used for event and time-related statistics since a reset() command was issued
        
        attribute "totalCycles", "number"
        attribute "totalElapsedTime", "number"
        attribute "minElapsedTime", "number"
        attribute "maxElapsedTime", "number"
        attribute "avgCycleRate", "number"
        
        command "reset", [[name:"Reset", description:"Reset all stopwatch statistics"]]
        
    }   
    
    preferences {
        input name: "timeUnit", type: "enum", title: "Elapsed time units", options: [["sec":"Seconds"],["min":"Minutes"],["hr":"Hours"],["day":"Days"]], required: true, multiple: false, defaultValue: "min"
        input name: "cycleRateUnit", type: "enum", title: "Cycle rate units", options: [["min":"per Minute"],["hr":"per Hour"],["day":"per Day"]], required: true, multiple: false, defaultValue: "day"
        input name: "updateInterval", type: "enum", title: "Clock time update interval:", options: [["5":"5 sec"],["10":"10sec"],["15":"15 sec"],["30":"30 sec"],["60":"1 min"]], required: true, multiple: false, defaultValue: "30"
        input "logEnable", "bool", title: "Enable debug logging?", defaultValue: false
    }                                                                
}

def reset() {

    // Initialize statistics attributes/state variables
    sendEvent(name: "totalCycles", value: 0, descriptionText: "Statistics reset totalCycles set to 0")
    sendEvent(name: "totalElapsedTime", value: 0, descriptionText: "Statistics reset totalElapsedTime set to 0", unit: timeUnit)
    sendEvent(name: "minElapsedTime", value: 0, descriptionText: "Statistics reset minElapsedTime set to 0", unit: timeUnit)
    sendEvent(name: "maxElapsedTime", value: 0, descriptionText: "Statistics reset maxElapsedTime set to 0", unit: timeUnit)
    sendEvent(name: "avgCycleRate", value: 0, descriptionText: "Statistics reset avgCycleRate set to 0", unit: cycleRateUnit)
}

def start() {
    
    // start() transition only valid from stopped, cancelled, or paused states
    
    if ((device.currentValue("sessionStatus") == "stopped") || (device.currentValue("sessionStatus") == "cancelled")) {
        logdebug("start: stopwatch entered started state")
        state.startTime = new Date()
        state.startTimeMs = now()
        state.elapsedPauseTimeMs = 0
        state.wasPaused = false
        sendEvent(name: "sessionStatus", value: "running", descriptionText: "Stopwatch started")
        runInMillis(100, 'updateStopwatch')
        def interval = updateInterval.toInteger()
        
        switch(updateInterval) {
            case "5":
            case "10":
            case "15":
            case "30":
                schedule("*/${interval} * * ? * *", updateStopwatch)
                break
            case "60":
            schedule("0 */1 * ? * *", updateStopwatch)
                break
        }
    } else if (device.currentValue("sessionStatus") == "paused") {
        state.resumeTimeMs = now()
        def deltat = state.resumeTimeMs - state.pauseTimeMs
        state.elapsedPauseTimeMs = state.elapsedPauseTimeMs + deltat
        sendEvent(name: "sessionStatus", value: "running", descriptionText: "Stopwatch resumed running after a pause of ${deltat} ms")
        logdebug("start: stopwatch entered running state after a paused of ${deltat} ms")
        updateStopwatch()
    } else {
        log.info("start: redundant start command - stopwatch already in started state")
    }
}

def push(button) {
    
    // Button 1=start, 2=stop, 3=pause, 4=cancel
    
    logdebug("push: button ${button} pushed")
    if (button == 1) { 
        start()
    } else if (button == 2) {
        stop()
    } else if (button == 3) {
        pause()
    } else if (button == 4) {
        cancel()
     } else {
        log.warning("Undefined button pushed: button ${button}")
    }
}

def stop() {
    
    // stop() calculates the elapsed time, then sends an event for stopped and the session timers. Add the data map of timestamps for convenience.
    
    if ((device.currentValue("sessionStatus") == "running") || (device.currentValue("sessionStatus") == "paused")) {
        unschedule()
        state.stopTime = new Date()
        state.stopTimeMs = now()
        sendEvent(name: "sessionStatus", value: "stopped", descriptionText: "Stopwatch stopped")
    
        et = calcDeltaTime()
    
        // Create a map object for the event data property
        def tsMap = [begin: state.startTime, end: state.stopTime, wasPaused: state.wasPaused]
    
        logdebug("stop: et=${et} ${timeUnit}, tsMap = ${tsMap}")
        sendEvent(name: "clockTime", value: et, unit: timeUnit, descriptionText: "Clock time since started")
        sendEvent(name: "elapsedTime", value: et, unit: timeUnit, descriptionText: "Session elapsed time updated", isStateChange: true, data: tsMap)

        tot_cyc = device.currentValue("totalCycles") + 1
        sendEvent(name: "totalCycles", value: tot_cyc, descriptionText: "Total cycles updated")
        
        cyc_rate = calcCycleRate()
        sendEvent(name: "avgCycleRate", value: cyc_rate, descriptionText: "Average cycle rate updated", unit: "per ${cycleRateUnit}")
        
        tot_et = (device.currentValue("totalElapsedTime") + et).round(4)
        sendEvent(name: "totalElapsedTime", value: tot_et, descriptionText: "Total elapsed time updated", unit: timeUnit)
                
        max_et = device.currentValue("maxElapsedTime") 
        if (et > max_et) sendEvent(name: "maxElapsedTime", value: et, descriptionText: "Maximum elapsed time updated", unit: timeUnit)
        
        min_et = device.currentValue("minElapsedTime") 
        if (min_et > 0) {
            if (et < min_et) sendEvent(name: "minElapsedTime", value: et, descriptionText: "Minimum elapsed time updated", unit: timeUnit)
        } else {
            sendEvent(name: "minElapsedTime", value: et, descriptionText: "Minimum elapsed time updated", unit: timeUnit)
        }
    } else {
        log.info("stop: no action taken - stopwatch not in running state")
    }
}

def cancel() {
    
    // Set stopwatch state, don't send elapsedTime event
    if ((device.currentValue("sessionStatus") == "running") || (device.currentValue("sessionStatus") == "paused")) {
        logdebug("cancel: stopwatch entered cancelled state")
        sendEvent(name: "sessionStatus", value: "cancelled", descriptionText: "Stopwatch cancelled")
        unschedule()
    }
}
    
def pause() {
    
    // Capture pause time so we can compute net elapsed time; set flag to indicate a pause state occured during this session
    
    if (device.currentValue("sessionStatus") == "running") {
        logdebug("pause: stopwatch entered paused state")
        state.pauseTimeMs = now()
        state.wasPaused = true
        sendEvent(name: "sessionStatus", value: "paused", descriptionText: "Stopwatch paused")
    } else {
        log.warning("pause: invalid pause command - stopwatch in paused, stopped or cancelled state")
    }
}

def updateStopwatch() {
    
    // Compute time since start, update attribute only if running
    
    if (device.currentValue("sessionStatus") == "running") {
        et = calcDeltaTime()
        logdebug("updateStopwatch: et=${et} ${timeUnit}")
        sendEvent(name: "clockTime", value: et, unit: timeUnit, descriptionText: "Elapsed time since started")
    }
}

def calcDeltaTime() {
    
    // Compute net elapsed time factoring in any pause state durations.

    nowMs = now()
    def et = 0.0
    deltat = (state.elapsedPauseTimeMs != null) ? (nowMs - state.startTimeMs - state.elapsedPauseTimeMs).toDouble() : (nowMs - state.startTimeMs).toDouble()    
  
    switch(timeUnit)
        {
        case "sec":
            et = (deltat / 1000).round(1)
            break
        case "min":
            et = (deltat / 1000 / 60).round(2)
            break
        case "hr":
            et = (deltat / 1000 / 60 / 60).round(3)
            break
        case "day":
            et = (deltat / 1000 / 60 / 60 / 24).round(4)
            break
    }
    return et
}

def calcCycleRate() {
    
    // Compute elapsed time and cycle rate since last reset() command.

    nowMs = now()
    cyc_rate = 0.0
    def cycles = device.currentValue("totalCycles").toDouble()
    def deltat = (nowMs - state.statsStartTimeMs).toDouble()
    
    switch(cycleRateUnit)
        {
        case "min":
            cyc_rate = (cycles / (deltat / 1000 / 60)).round(3)
            break
        case "hr":
            cyc_rate = (cycles / (deltat / 1000 / 60 / 60)).round(2)
            break
        case "day":
            cyc_rate = (cycles / (deltat / 1000 / 60 / 60 / 24)).round(1)
            break
    }
    
    logdebug("calcCycleRate: nowMs=${nowMs}, cycles=${cycles}, deltat=${deltat}, cyc_rate=${cyc_rate}")
    
    return cyc_rate
}

def installed() {
    state.startTime = new Date()
    state.stopTime = new Date()
    state.statsStartTime = new Date()
    state.startTimeMs = 0
    state.stopTimeMs = 0
    state.statsStartTimeMs = now()
    state.pauseTimeMs = 0
    state.resumeTimeMs = 0
    state.elapsedPauseTimeMs = 0
    state.wasPaused = false
    
    sendEvent(name: "sessionStatus", value: "stopped", descriptionText: "Stopwatch stopped")
    reset()
}

def initialize() {
}

def updated() {
    sendEvent(name: "units", value: timeUnit, descriptionText: "Stopwatch time units changed to ${timeUnit}")
    if (!state.statsStartTime) state.statsStartTime = new Date()
    if (!state.statsStartTimeMs) state.statsStartTimeMs = now()
    
    //
    
    sendEvent(name: "totalCycles", value: 0, descriptionText: "Total cycles updated")
    sendEvent(name: "avgCycleRate", value: 0, descriptionText: "Average cycle rate updated", unit: "per ${cycleRateUnit}")
    sendEvent(name: "totalElapsedTime", value: 0, descriptionText: "Total elapsed time updated", unit: timeUnit)
    sendEvent(name: "maxElapsedTime", value: 0, descriptionText: "Maximum elapsed time updated", unit: timeUnit)
    sendEvent(name: "minElapsedTime", value: 0, descriptionText: "Minimum elapsed time updated", unit: timeUnit)
    
    //
    
}

private logdebug(message) {
  if (logEnable) {
    log.debug "$message"
  }
}
