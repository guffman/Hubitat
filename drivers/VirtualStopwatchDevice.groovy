/**
 *  Virtual Stopwatch Device
 *
 *  After a Start and a subsequent Stop command, calculate the elapsed time using the chosen time units, timed session statistics. 
 *  Sends a "session" event with the total elapsed time, and a data map of the starting and ending timestamps. 
 *  The Stop command triggers the session event.
 *  The Cancel command resets the stopwatch state, but sends no elapsed time events, nor computes session statistics.
 *  Periodic elapsed time events are issued using a dedicated runningTime attribute, only while the stopwatch is running. 
 *
 *  Button capabilities are added for convenience:
 *      Button 1 pushed - starts the stopwatch
 *      Button 2 pushed - stops the stopwatch
 *      Button 3 pushed - cancels the running stopwatch
 *.     Button 4 pushed - pause (not implemented)
 *
 *  Switch capabilities are added for convenience:
 *      On  - starts the stopwatch
 *      Off - stops the stopwatch
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
 *    2021-06-23  Marc Chevis    Added some statistics attributes, switch capability, removed pause feature
 * 
 */

metadata {
    definition (name: "Virtual Stopwatch Device", namespace: "guffman", author: "Guffman", importUrl: "") {
        capability "Configuration"
        capability "Refresh"
        capability "PushableButton"
        capability "Switch"
        capability "TimedSession"
        
        // These attributes are used for the current stopwatch timing session
        attribute "elapsedTime", "number"
        attribute "runningTime", "number"
        attribute "units", "string"
        attribute "sessionStartTime", "date"
        attribute "sessionStopTime", "date"
        
        // These attributes are used for event and time-related statistics across timing sessions, since a reset() command was issued
        
        attribute "totalCycles", "number"
        attribute "totalElapsedTime", "number"
        attribute "minElapsedTime", "number"
        attribute "maxElapsedTime", "number"
        attribute "avgCycleRate", "number"
        attribute "dutyCycle", "number"
        attribute "statsStartTime", "date"
        
        command "reset", [[name:"Reset", description:"Reset all stopwatch statistics"]]
        command "clear", [[name:"Clear", description:"Clear state and schedules"]]
    }   
    
    preferences {
        input name: "timeUnit", type: "enum", title: "Elapsed time units", options: [["sec":"Seconds"],["min":"Minutes"],["hr":"Hours"],["day":"Days"]], required: true, multiple: false, defaultValue: "min"
        input name: "cycleRateUnit", type: "enum", title: "Cycle rate units", options: [["min":"per Minute"],["hr":"per Hour"],["day":"per Day"]], required: true, multiple: false, defaultValue: "day"
        input name: "updateInterval", type: "enum", title: "Stopwatch running time update interval:", options: [["5":"5 sec"],["10":"10sec"],["15":"15 sec"],["30":"30 sec"],["60":"1 min"]], required: true, multiple: false, defaultValue: "30"
        input "logEnable", "bool", title: "Enable debug logging?", defaultValue: false
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
        cancel()
    } else if (button == 4) {
        pause()
     } else {
        log.warning("Undefined button pushed: button ${button}")
    }
}

def on() {
    start()
}

def off() {
    stop()
}

def start() {
    
    // start() transition only valid from stopped or cancelled states
    
    if ((device.currentValue("sessionStatus") == "stopped") || (device.currentValue("sessionStatus") == "cancelled")) {
        logdebug("start: stopwatch entered started state")
        
        // Capture start times and update stopwatch state, start time
        
        state.startTimeMs = now()
        startTime = new Date(state.startTimeMs).format("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
        
        sendEvent(name: "sessionStatus", value: "running", descriptionText: "Stopwatch started")
        sendEvent(name: "switch", value: "on", descriptionText: "Stopwatch started")
        sendEvent(name: "sessionStartTime", value: startTime, descriptionText: "Session start time updated")
        
    } else {
        logdebug("start: redundant start command - stopwatch already in started state")
    }
}

def stop() {
    
    // stop() calculates the elapsed time, then sends an event for stopped and the session timers. Add the data map of timestamps for convenience.
    
    if (device.currentValue("sessionStatus") == "running") {
        logdebug("stop: stopwatch entered stopped state")
        
        // Capture stop times and update stopwatch state
        
        state.stopTimeMs = now()

        sendEvent(name: "sessionStatus", value: "stopped", descriptionText: "Stopwatch stopped")
        sendEvent(name: "switch", value: "off", descriptionText: "Stopwatch stopped")

        // Compute results for the recent timing session and update attributes.
        logdebug("stop: state.startTimeMs=${state.startTimeMs}, state.stopTimeMs=${state.stopTimeMs}")
        et = deltaTime(state.startTimeMs, state.stopTimeMs, timeUnit)
        
        // Create a map object for the event data property
        startTime = new Date(state.startTimeMs).format("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
        stopTime = new Date(state.stopTimeMs).format("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                stopTime = new Date(state.stopTimeMs).format("yyyy-MM-dd'T'HH:mm:ss.SSSZ")

        def tsMap = [start: startTime, stop: stopTime]
        logdebug("stop: et=${et} ${timeUnit}, tsMap = ${tsMap}")
        
        // Send the recent timing session attribute updates
        sendEvent(name: "runningTime", value: et, unit: timeUnit, descriptionText: "Elapsed time since stopwatch started")
        sendEvent(name: "elapsedTime", value: et, unit: timeUnit, descriptionText: "Session elapsed time updated", isStateChange: true, data: tsMap)
        sendEvent(name: "sessionStopTime", value: stopTime, descriptionText: "Session stop time updated")

        // Increment the total cycles statistic and update the attribute
        cyc_tot = device.currentValue("totalCycles") + 1
        sendEvent(name: "totalCycles", value: cyc_tot, descriptionText: "Total cycles updated")
        
        // Compute the cycle rate statistic and update the attribute
        cyc_rate = cycleRate(cyc_tot)
        sendEvent(name: "avgCycleRate", value: cyc_rate, descriptionText: "Average cycle rate updated", unit: "per ${cycleRateUnit}")
        
        // Compute the elapsed time totalizer statistic and update the attribute
        et_tot = (device.currentValue("totalElapsedTime") + et).round(4)
        sendEvent(name: "totalElapsedTime", value: et_tot, descriptionText: "Total elapsed time updated", unit: timeUnit)
        
        // Compute the duty cycle statistic and update the attribute
        state.statsElapsedTimeMs = state.statsElapsedTimeMs + (state.stopTimeMs - state.startTimeMs)
        duty_cyc = (100 * state.statsElapsedTimeMs / (state.stopTimeMs - state.statsStartTimeMs)).toInteger() 
        sendEvent(name: "dutyCycle", value: duty_cyc, descriptionText: "Duty cycle updated", unit: "%")
        
        // Compute the maximum elapsed time statistic and update the attribute
        et_max = device.currentValue("maxElapsedTime") 
        if (et > et_max) sendEvent(name: "maxElapsedTime", value: et, descriptionText: "Maximum elapsed time updated", unit: timeUnit)
        
        // Compute the minimum elapsed time statistic and update the attribute
        et_min = device.currentValue("minElapsedTime") 
        if (et_min > 0) {
            if (et < et_min) sendEvent(name: "minElapsedTime", value: et, descriptionText: "Minimum elapsed time updated", unit: timeUnit)
        } else {
            sendEvent(name: "minElapsedTime", value: et, descriptionText: "Minimum elapsed time updated", unit: timeUnit)
        }
      
    } else {
        logdebug("stop: no action taken - stopwatch not in running state")
    }
}

def cancel() {
    
    // Set stopwatch state, but don't update elapsedTime or statistics attributes.
    
    if (device.currentValue("sessionStatus") == "running") {
        logdebug("cancel: stopwatch entered cancelled state")
        sendEvent(name: "sessionStatus", value: "cancelled", descriptionText: "Stopwatch cancelled")
        sendEvent(name: "switch", value: "off", descriptionText: "Stopwatch cancelled")
    }
}
    
def pause() {
    log.info("pause: stopwatch paused command not implemented")
}

def reset() {

    // Initialize statistics attributes/state variables
    
    state.statsElapsedTimeMs = 0
    state.statsStartTimeMs = now()
    statsStartTime = new Date(state.statsStartTimeMs).format("yyyy-MM-dd'T'HH:mm:ss.SSSZ")

    sendEvent(name: "statsStartTime", value: statsStartTime, descriptionText: "Statistics reset datetime")
    sendEvent(name: "totalCycles", value: 0, descriptionText: "Statistics reset totalCycles set to 0")
    sendEvent(name: "totalElapsedTime", value: 0, descriptionText: "Statistics reset totalElapsedTime set to 0", unit: timeUnit)
    sendEvent(name: "minElapsedTime", value: 0, descriptionText: "Statistics reset minElapsedTime set to 0", unit: timeUnit)
    sendEvent(name: "maxElapsedTime", value: 0, descriptionText: "Statistics reset maxElapsedTime set to 0", unit: timeUnit)
    sendEvent(name: "avgCycleRate", value: 0, descriptionText: "Statistics reset avgCycleRate set to 0", unit: cycleRateUnit)
    sendEvent(name: "dutyCycle", value: 0, descriptionText: "Statistics reset dutyCycle set to 0", unit: "%")

}

def updateStopwatch() {
    
    // Compute time since start to ct update attribute only if running
    
    if (device.currentValue("sessionStatus") == "running") {
        nowMs = now()
        et = deltaTime(state.startTimeMs, nowMs, timeUnit)
        logdebug("updateStopwatch: et=${et} ${timeUnit}")
        sendEvent(name: "runningTime", value: et, unit: timeUnit, descriptionText: "Elapsed time since stopwatch started")
    }
}

def deltaTime(begin, end, units) {
    
    // Compute net elapsed time (in specified time units) between begin and end (in msec)
    logdebug("deltaTime: begin=${begin}, end=${end}, units=$units}")
    dt = (end - begin).toDouble()
    def et = 0.0
  
    switch(units)
        {
        case "sec":
            et = (dt / 1000).round(1)
            break
        case "min":
            et = (dt / 1000 / 60).round(2)
            break
        case "hr":
            et = (dt / 1000 / 60 / 60).round(3)
            break
        case "day":
            et = (dt / 1000 / 60 / 60 / 24).round(4)
            break
    }
    return et
}
           
def cycleRate(cycles) {
    
    // Compute elapsed time and cycle rate since last reset() command.

    cyc_rate = 0.0
    def dt = deltaTime(state.statsStartTimeMs, state.stopTimeMs, cycleRateUnit)
    cyc_rate = (cycles / dt).round(3)
 
    logdebug("calcCycleRate: cycles=${cycles}, deltat=${dt}, cyc_rate=${cyc_rate}")
    
    return cyc_rate
}

def installed() {
    
    // Called when device created. 
    log.info "installed..."
    configure()
    
    
}

def configure() {
    
    // Initialize timestamps and statistics counters, set up scheduled items
    log.info "configure..."
    
    state.startTimeMs = 0
    state.stopTimeMs = 0
    
    sendEvent(name: "sessionStatus", value: "stopped", descriptionText: "Stopwatch stopped")
    sendEvent(name: "switch", value: "off", descriptionText: "Stopwatch stopped")
    sendEvent(name: "runningTime", value: 0, unit: timeUnit, descriptionText: "Stopwatch elapsed time reset")
    sendEvent(name: "units", value: timeUnit, descriptionText: "Stopwatch time units set to ${timeUnit}")
    
    // Initialize stats
    
    reset()
    
    // Set up the periodic 'stopwatch running' update task
    
    scheduleRefresh()
}

def scheduleRefresh() {
    
    def interval = updateInterval.toInteger()
        
    switch(updateInterval) {
        case "5":
        case "10":
        case "15":
        case "30":
            schedule("*/${interval} * * ? * *", refresh)
            break
        case "60":
            schedule("0 */1 * ? * *", refresh)
            break
    }
    logdebug("scheduleRefresh: rescheduled for every ${updateInterval} sec")
}

def initialize() {
    log.info "initialize..."
}

def updated() {
    
    // Called when preferences are updated
    log.info "updated..."

    // If time units are changed, any related attribute values must be re-scaled appropriately
    
    if (timeUnit != device.currentValue("units")) {
        // transform attribute values
        sendEvent(name: "units", value: timeUnit, descriptionText: "Stopwatch time units changed to ${timeUnit}")
    }
       
    // Compute the cycle rate statistic and update the attribute
    //cyc_rate = cycleRate(tot_cyc)
    //sendEvent(name: "avgCycleRate", value: cyc_rate, descriptionText: "Average cycle rate time base changed, value recalculated", unit: "per ${cycleRateUnit}")
    
    unschedule()
    scheduleRefresh()
}

def refresh() {
    updateStopwatch()
}

def clear() {
    stop()
    reset()
    unschedule()
    state.clear()
}

private logdebug(message) {
  if (logEnable) {
    log.debug "$message"
  }
}
