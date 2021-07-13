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
 *      Button 4 pushed - resets the stopwatch statistics and related counters
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
 *    2021-07-06  Marc Chevis    Revised statistics reset logic
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
        attribute "cycles", "number"
        
        // These attributes are used for event and time-related statistics across timing sessions, since a reset() command was issued
        attribute "totalCycles", "number"
        attribute "totalElapsedTime", "number"
        attribute "minElapsedTime", "number"
        attribute "maxElapsedTime", "number"
        attribute "dutyCycle", "number"
        attribute "statsStartTime", "date"
        attribute "statsStopTime", "date"
        attribute "statsElapsedDays", "number"
        
        command "reset", [[name:"Reset", description:"Reset all stopwatch statistics and update statistics attributes"]]
        command "clear", [[name:"Clear", description:"Clear state values, schedules, and reset statistics"]]
    }   
    
    preferences {
        input name: "timeUnit", type: "enum", title: "Elapsed time units", options: [["sec":"Seconds"],["min":"Minutes"],["hr":"Hours"],["day":"Days"]], required: true, multiple: false, defaultValue: "min"
        input name: "updateInterval", type: "enum", title: "Stopwatch running time update interval:", options: [["5":"5 sec"],["10":"10sec"],["15":"15 sec"],["30":"30 sec"],["60":"1 min"]], required: true, multiple: false, defaultValue: "30"
        input "logEnable", "bool", title: "Enable debug logging?", defaultValue: false
    }                                                                
}

def push(button) {
    
    // Button 1=start, 2=stop, 3=pause, 4=reset stats
    
    logdebug("push: button ${button} pushed")
    if (button == 1) { 
        start()
    } else if (button == 2) {
        stop()
    } else if (button == 3) {
        cancel()
    } else if (button == 4) {
        reset()
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
        
        // Capture stop time, elaspsed time and update stopwatch state
        
        state.stopTimeMs = now()
        et = deltaTime(state.startTimeMs, state.stopTimeMs, timeUnit)
        sendEvent(name: "sessionStatus", value: "stopped", descriptionText: "Stopwatch stopped")
        sendEvent(name: "switch", value: "off", descriptionText: "Stopwatch stopped")
        logdebug("stop: state.startTimeMs=${state.startTimeMs}, state.stopTimeMs=${state.stopTimeMs}, et=${et}")
  
        // Create a map object for the elapsedTime event data property
        startTime = new Date(state.startTimeMs).format("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
        stopTime = new Date(state.stopTimeMs).format("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
        def tsMap = [start: startTime, stop: stopTime]
        
        // Send the recent timing session attribute updates
        sendEvent(name: "runningTime", value: et, unit: timeUnit, descriptionText: "Elapsed time since stopwatch started")
        sendEvent(name: "elapsedTime", value: et, unit: timeUnit, descriptionText: "Session elapsed time updated", isStateChange: true, data: tsMap)
        sendEvent(name: "sessionStopTime", value: stopTime, descriptionText: "Session stop time updated")

        // Increment the total cycles counter - statistics state varable and the attribute
        state.totalCycles = state.totalCycles + 1
        sendEvent(name: "cycles", value: state.totalCycles, descriptionText: "Cycle count incremented")

        // Update the elapsed time totalizer statistics
        state.totalElapsedTime = state.totalElapsedTime + et
        state.totalElapsedTimeMs = state.totalElapsedTimeMs + (state.stopTimeMs - state.startTimeMs)
        
        // Compute the minimum and maximum elapsed time statistics 
        if (et > state.maxElapsedTime) state.maxElapsedTime = et
        if (et < state.minElapsedTime) state.minElapsedTime = et
        if (state.minElapsedTime == 0) {
            state.minElapsedTime = et
        } else if (et < state.minElapsedTime) {
            state.minElapsedTime = et
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
    
    // Capture timestamps, compute interval statistics, clean up some precision
    statsStopTimeMs = now()
    statsStopTime = new Date(statsStopTimeMs).format("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
    days = deltaTime(state.statsStartTimeMs, statsStopTimeMs, "day")
    tot_et = state.totalElapsedTime
    Long dc = (100L * state.totalElapsedTimeMs) / (statsStopTimeMs - state.statsStartTimeMs)
    Integer dcPct = dc.toInteger()

    logdebug("reset: statsStartTime=${state.statsStartTime}, statsStopTime=${statsStopTime}, days=${days}, totalCycles=${state.totalCycles}, dutyCycle=${dcPct}")
    
    // Create a map object for the statistics events' timestamps data property
    def tsMap = [start: state.statsStartTime, stop: statsStopTime, duration: days]
    
    // Send statistics events
    sendEvent(name: "totalCycles", value: state.totalCycles, descriptionText: "Total cycles since ${state.statsStartTime}", data: tsMap)
    sendEvent(name: "totalElapsedTime", value: tot_et, descriptionText: "Total elapsed time since ${state.statsStartTime}", unit: timeUnit, data: tsMap)
    sendEvent(name: "dutyCycle", value: dcPct, descriptionText: "Duty cycle since ${state.statsStartTime}", unit: "%", data: tsMap)
    sendEvent(name: "minElapsedTime", value: state.minElapsedTime, descriptionText: "Minimum elapsed time since ${state.statsStartTime}", unit: timeUnit, data: tsMap)
    sendEvent(name: "maxElapsedTime", value: state.maxElapsedTime, descriptionText: "Maximum elapsed time since ${state.statsStartTime}", unit: timeUnit, data: tsMap)
    sendEvent(name: "statsStopTime", value: statsStopTime, descriptionText: "Statistics interval ending datetime")
    sendEvent(name: "statsStartTime", value: state.statsStartTime, descriptionText: "Statistics interval starting datetime")
    sendEvent(name: "statsElapsedDays", value: days, descriptionText: "Statistics interval duration", unit: 'day')
   
    // Now that we've sent the statistics interval events, initialize the statistics state variables
    resetStateStatistics()
    
    // Reset the cycles counter attribute
    sendEvent(name: "cycles", value: 0, descriptionText: "Reset cycles counter")
}

def resetStateStatistics() {
    state.totalCycles = 0
    state.totalElapsedTime = 0
    state.totalElapsedTimeMs = 0
    state.minElapsedTime = 0
    state.maxElapsedTime = 0
    ct = now()
    state.statsStartTimeMs = ct
    state.statsStartTime = new Date(ct).format("yyyy-MM-dd'T'HH:mm:ss.SSSZ")    
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
    Long dt = (end - begin) / 1000L
    Double dtd = dt.toDouble()
    
    switch(units)
        {
        case "sec":
            et = dt
            break
        case "min":
            et = (dtd / 60.0).round(2)
            break
        case "hr":
            et = (dtd / 3600.0).round(3)
            break
        case "day":
            et = (dtd / 86400.0).round(4)
            break
    }
    
    logdebug("deltaTime: begin=${begin}, end=${end}, units=${units}, dt=${dt}, dtd=${dtd}, et=${et}")
    return et
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
    resetStateStatistics()
    
    sendEvent(name: "sessionStatus", value: "stopped", descriptionText: "Stopwatch stopped")
    sendEvent(name: "switch", value: "off", descriptionText: "Stopwatch stopped")
    sendEvent(name: "runningTime", value: 0, unit: timeUnit, descriptionText: "Stopwatch elapsed time reset")
    sendEvent(name: "cycles", value: 0, descriptionText: "Stopwatch cycle counter reset")
    sendEvent(name: "units", value: timeUnit, descriptionText: "Stopwatch time units set to ${timeUnit}")

    // Set up the periodic 'stopwatch running' update task and the statistics interval 'reset' task
    
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
    logdebug("scheduleRefresh: stopwatch running updates rescheduled for every ${updateInterval} sec")
}

def initialize() {
    log.info "initialize..."
}

def updated() {
    
    // Called when preferences are updated
    log.info "updated..."

    // If time units are changed, any related attribute values must be re-scaled appropriately
    
    if (timeUnit != device.currentValue("units")) {
        // transform attribute values???
        sendEvent(name: "units", value: timeUnit, descriptionText: "Stopwatch time units changed to ${timeUnit}")
    }
    
    unschedule()
    scheduleRefresh()
}

def refresh() {
    updateStopwatch()
}

def clear() {
    stop()
    unschedule()
    state.clear()
}

private logdebug(message) {
  if (logEnable) {
    log.debug "$message"
  }
}
