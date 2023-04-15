/**
 *  MIT License
 *  Copyright 2020 Jonathan Bradshaw (jb@nrgup.net)
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 *
 *    Date          By         Description
 *    ----------    -------    -----------
 *    04/14/2023    Guffman    Modified for only local communication and for Tuya Air Conditioner protocol.
*/
import com.hubitat.app.DeviceWrapper
import hubitat.helper.HexUtils
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.*
import java.util.regex.Matcher
import java.util.Random

metadata {
    definition (name: 'Tuya Local Air Conditioner', namespace: 'guffman', author: 'Guffman', importUrl: '') {
        
        singleThreaded: true
        capability 'Actuator'
        capability 'Sensor'
        capability 'Thermostat'

        attribute "supportedThermostatFanModes", "JSON_OBJECT"
		attribute "supportedThermostatModes", "JSON_OBJECT"
        attribute "ecoMode", "STRING"
        attribute 'retries', 'number'
        attribute 'errors', 'number'
        
		command "setSupportedThermostatFanModes", ["JSON_OBJECT"]
		command "setSupportedThermostatModes", ["JSON_OBJECT"]
        
        command "dry"
        command ("ecoMode", [[name:"Economy Mode", type:"ENUM", description:"Toggle Economy Mode", constraints:["On", "Off"]]])
        command "fanOnly"
        command "fanAuto"
        command "fanLow"
        command "fanMedium"
        command "fanHigh"
        command 'refresh'
        
        command 'sendCustomDps', [[name: 'Dps', type: 'NUMBER'], [name: 'Value', type: 'STRING']]
    }
}

preferences {
    section {
        input name: 'ipAddress',
              type: 'text',
              title: 'Device IP',
              required: true,
              default_value: '192.168.40.40'

        input name: 'repeat',
              title: 'Command Retries',
              type: 'number',
              required: true,
              range: '0..5',
              defaultValue: '3'

        input name: 'timeoutSecs',
              title: 'Command Timeout (sec)',
              type: 'number',
              required: true,
              range: '1..5',
              defaultValue: '1'

        input name: 'heartbeatSecs',
              title: 'Heartbeat interval (sec)',
              type: 'number',
              required: true,
              range: '0..60',
              defaultValue: '20'
        
        input name: 'id',
              title: 'Device ID:',
              type: 'text',
              required: true,
              defaultValue: '6053105810521c05cbe7'

        input name: 'localKey',
              title: 'Device Local Key:',
              type: 'text',
              required: true,
              defaultValue: '96a0f792ef5adef5'

        input name: 'logEnable',
              type: 'bool',
              title: 'Enable debug logging',
              required: false,
              defaultValue: true

        input name: 'txtEnable',
              type: 'bool',
              title: 'Enable descriptionText logging',
              required: false,
              defaultValue: true
    }
}

// Tuya parameter definitions for an air conditioner
@Field static final Integer powerDps = 1
@Field static final Integer setptDps = 2
@Field static final Integer tempDps  = 3
@Field static final Integer modeDps  = 4
@Field static final Integer fanDps   = 5
@Field static final Integer ecoDps   = 8

// Queue used for ACK tracking
@Field static queues = new ConcurrentHashMap<String, SynchronousQueue>()

/**
 *  Hubitat Driver Event Handlers
 */
// Called to keep device connection open
void heartbeat() {
    SynchronousQueue queue = getQ()
    LOG.debug "sending heartbeat"
    synchronized (queue) {
        tuyaSendCommand(id, ipAddress, localKey, null, 'HEART_BEAT')
        if (queue.poll(timeoutSecs, TimeUnit.SECONDS)) {
            LOG.debug "received heartbeat"
        } else {
            LOG.warn "no response to heartbeat"
        }
    }

    if (heartbeatSecs) { runIn(heartbeatSecs, 'heartbeat') }
}

// Called when the device is first created
void installed() {
    LOG.info "driver installed"
}

// Called to initialize
void initialize() {
    LOG.info "driver initialized"
    sendEvent ([ name: 'retries', value: 0, descriptionText: 'reset' ])
    sendEvent ([ name: 'errors', value: 0, descriptionText: 'reset' ])
    
    // Set air conditioner supported modes   
    setSupportedThermostatFanModes(JsonOutput.toJson(["auto","high","med","low"]))
	setSupportedThermostatModes(JsonOutput.toJson(["auto", "cool", "dry", "economy", "fan only", "off"]))    
  
    heartbeat()
}

// Called when the device parameters are changed
void updated() {
    LOG.info "driver configuration updated"
    LOG.debug settings
    unschedule()
    initialize()
    if (logEnable) { runIn(1800, 'logsOff') }
}

// Component command to turn on device
void on() {
    LOG.info "sending command: on"
    if (!repeatCommand([ (powerDps as String): true ])) LOG.error "On command failed"
}

// Component command to turn off device
void off() {
    LOG.info "sending command: off"
    if (!repeatCommand([ (powerDps as String): false ])) LOG.error "Off command failed"
}

// Component command to refresh device
void refresh() {
    LOG.info 'Refresh command issued'
    tuyaSendCommand(id, ipAddress, localKey, null, 'DP_QUERY')
}

// Air Conditioner Modes
void setSupportedThermostatFanModes(fanModes) {
    descriptionText = "${device.displayName} ${name} supportedThermostatFanModes set to ${fanModes}"
	sendEvent(name: "supportedThermostatFanModes", value: fanModes, descriptionText: descriptionText)
}

void setSupportedThermostatModes(modes) {
    descriptionText = "${device.displayName} ${name} supportedThermostatModes set to ${modes}"
	sendEvent(name: "supportedThermostatModes", value: modes, descriptionText: descriptionText)
}

void auto() {
    LOG.info "sending command: auto"
    if (!repeatCommand([ (powerDps as String): true, (modeDps as String): 'auto' ])) LOG.error "Auto command failed"
}    
    
void cool() {
    LOG.info "sending command: cool"
    if (!repeatCommand([ (powerDps as String): true, (modeDps as String): 'cold', (setptDps as String): 76 ])) LOG.error "Cool command failed"
}    

void dry() {
    LOG.info "sending command: dry"
    if (!repeatCommand([ (powerDps as String): true, (modeDps as String): 'wet' ])) LOG.error "Dry command failed"    
}    

void heat() {
    LOG.warn "heat mode not supported by this device"
}

void emergencyHeat() {
    LOG.warn "emergencyHeat mode not supported by this device"
}

def ecoMode(mode) {
    Boolean eco = (mode == 'On' ? true : false)
    LOG.info "sending command: Economy Mode ${mode} requested"
    if (!repeatCommand([ (ecoDps as String): eco ])) LOG.error "Economy command failed"
} 

def fanOnly() {
    LOG.info "sending command: fanOnly"
    if (!repeatCommand([ (powerDps as String): true, (modeDps as String): 'wind' ])) LOG.error "FanOnly command failed"    
}    
 
// Temperature Setpoints
void setCoolingSetpoint(spt) {
    LOG.debug "setCoolingSetpoint ${spt} degF was called"
    def setpt = spt.toInteger()
        if (!repeatCommand([ (setptDps as String): setpt ])) LOG.error "setCoolingSetpoint command failed"
}

def setHeatingSetpoint(spt) {
    log.warn "setHeatingSetpoint redirected to setCoolingSetpoint"
    setCoolingSetpoint(spt)
}

// Air Conditioner Fan Modes
def fanAuto() {
    LOG.info "sending command: Fan Auto requested"
    if (!repeatCommand([ (fanDps as String): '4' ])) LOG.error "fanAuto command failed"    
} 

def fanLow() {
    LOG.info "sending command: Fan Low requested"
    if (!repeatCommand([ (fanDps as String): '3' ])) LOG.error "fanLow command failed"    
}  

def fanMedium() {
    LOG.info "sending command: Fan Medium requested"
    if (!repeatCommand([ (fanDps as String): '2' ])) LOG.error "fanMedium command failed"    
} 

def fanHigh() {
    LOG.info "sending command: Fan High requested"
    if (!repeatCommand([ (fanDps as String): '1' ])) LOG.error "fanHigh command failed"    
} 

def fanCirculate() {
    LOG.warn "fanMode circulate not supported"
} 

// Send custom Dps command
void sendCustomDps(BigDecimal dps, String value) {
    LOG.info "sending DPS ${dps} command ${value}"
    switch (value.toLowerCase()) {
        case 'true':
            repeatCommand([ (dps): true ])
            return
        case 'false':
            repeatCommand([ (dps): false ])
            return
    }

    repeatCommand([ (dps): value ])
}

private void logsOff() {
    LOG.warn 'debug logging disabled'
    device.updateSetting('logEnable', [value: 'false', type: 'bool'] )
}

private static String getIPFromInt(long ipaslong) {
    return String.format("%d.%d.%d.%d",
        (ipaslong >>> 24) & 0xff,
        (ipaslong >>> 16) & 0xff,
        (ipaslong >>>  8) & 0xff,
        (ipaslong       ) & 0xff)
}

public static long ipToLong(String ipAddress) {
    long result = 0
    String[] ipAddressInArray = ipAddress.split('\\.')
    for (int i = 3; i >= 0; i--) {
        long ip = Long.parseLong(ipAddressInArray[3 - i])
        result |= ip << (i * 8)
    }

    return result;
}

private SynchronousQueue getQ() {
    return queues.computeIfAbsent(device.id) { k -> new SynchronousQueue() }
}

// Socket status updates
void socketStatus(String message) {
    if (message.contains('error')) {
        LOG.error "socket ${message}"
        int val = (device.currentValue('errors') ?: 0) as int
        sendEvent ([ name: 'errors', value: val + 1, descriptionText: message ])
    } else {
        LOG.info "socket ${message}"
    }
}

// parse responses from device
void parse(String message) {
    if (!message) { return }
    Map result = tuyaDecode(HexUtils.hexStringToByteArray(message), localKey)
    LOG.debug "parse: result=${result}"
    if (result.error) {
        LOG.error "received error ${result.error}"
        increaseErrorCount(result.error)
    } else if (result.commandByte == 7 || result.commandByte == 9) { // COMMAND or HEARTBEAT ACK
        if (!getQ().offer(result)) { LOG.warn "result received without waiting thread" }
    } else if (result.commandByte == 8 || result.commandByte == 10 ) { // STATUS or QUERY RESULTS
        Map json = new JsonSlurper().parseText(result.text)
        LOG.debug "parse: json=${json}"
        parseDeviceState(json.dps)
    }
}

private void parseDeviceState(Map dps) {

//    Map functions = getFunctions(device)
//    def func = device.getDataValue('functions')
//    Map functions = new JsonSlurper().parseText(device.getDataValue('functions'))
    LOG.debug "parseDeviceState: dps ${dps}, device=${device}"    
    List<Map> events = []
    def unit = "°${location.temperatureScale}"
    
    // Must check for mode first, since device on/off state is a separate Dps
    if (dps.containsKey(modeDps as String)) {
        String tuya_mode = dps[modeDps as String]
        switch (tuya_mode) {
           case "auto": 
                mode = "auto"
                operatingState = "cooling"
                break
            case "cold": 
                mode = "cool"
                operatingState = "cooling"
                break
            case "wet": 
                mode = "dry"
                operatingState = "cooling"
                break
            case "wind":
                mode = "cool"
                operatingState = "fan only"
                break
            case "eco": 
                mode = "economy"
                operatingState = "cooling"
                break
            default:
                LOG.error "Undefined thermostatOperatingState" 
        }
    } else {        
        mode = device.currentValue('thermostatMode')
        operatingState = device.currentValue('thermostatOperatingState')
    }
    
    // Device on/off state
    if (dps.containsKey(powerDps as String)) {
        String tuya_power = dps[powerDps as String] ? 'on' : 'off'
        
        if (tuya_power == 'off') {
            mode = "off"
            operatingState = "idle"
        }
    }
    
    name = "thermostatMode"
    descriptionText = "${device.displayName} ${name} was set to ${mode}"
    events << [ name: name, value: mode, descriptionText: descriptionText ]
        
    name = "thermostatOperatingState"
    descriptionText = "${device.displayName} ${name} was set to ${operatingState}"
    events <<[ name: name, value: operatingState, descriptionText: descriptionText ]

    
    // Cooling setpoint deg F
    if (dps.containsKey(setptDps as String)) {
        Integer setpt = dps[setptDps as String]

        name = "coolingSetpoint"
		descriptionText = "${device.displayName} ${name} was set to ${setpt}${unit}"
        events << [ name: name, value: setpt, descriptionText: descriptionText, unit: unit ]
		
		name = "heatingSetpoint"
		descriptionText = "${device.displayName} ${name} was set to ${setpt}${unit}"
        events << [ name: name, value: setpt, descriptionText: descriptionText, unit: unit ]
        
        name = "thermostatSetpoint"
		descriptionText = "${device.displayName} ${name} was set to ${setpt}${unit}"
        events << [ name: name, value: setpt, descriptionText: descriptionText, unit: unit ]
    }

    // Current temperature deg F
    if (dps.containsKey(tempDps as String)) {
        Integer temp = dps[tempDps as String]

        name = "temperature"
		descriptionText = "${device.displayName} ${name} was set to ${temp}${unit}"
        events << [ name: name, value: temp, descriptionText: descriptionText, unit: unit ]
    }
    
    // Fan mode
    if (dps.containsKey(fanDps as String)) {
        String tuya_fan = dps[fanDps as String]
        switch (tuya_fan) {
            case '1': 
                fanMode = "high"
                break
            case '2': 
                fanMode = "medium"
                break
            case '3': 
                fanMode = "low"
                break
            case '4':
                fanMode = "auto"
                break
            default:
                LOG.error "Undefined fanMode" 
        }
        
        name = "thermostatFanMode"
        descriptionText = "${device.displayName} ${name} was set to ${fanMode}"
        events << [ name: name, value: fanMode, descriptionText: descriptionText ]
    }

    // Money Saver On/Off, aka Eco Mode
    if (dps.containsKey(ecoDps as String)) {
        String eco = dps[ecoDps as String] ? 'on' : 'off'
        name = "ecoMode"
        descriptionText = "${device.displayName} ${name} was set to ${eco}"
        events << [ name: name, value: eco, descriptionText: descriptionText ]      
    }    
    
    events.each { e ->
        if (device.currentValue(e.name) != e.value) {
            if (e.descriptionText && txtEnable) { LOG.info "${e.descriptionText}" }
            sendEvent(e)
        }
    }
}

private void increaseErrorCount(msg = '') {
    int val = (device.currentValue('errors') ?: 0) as int
    sendEvent ([ name: 'errors', value: val + 1, descriptionText: msg ])
}

private void increaseRetryCount(msg = '') {
    int val = (device.currentValue('retries') ?: 0) as int
    sendEvent ([ name: 'retries', value: val + 1, descriptionText: msg ])
}

private Map repeatCommand(Map dps) {
    Map result
    SynchronousQueue queue = getQ()
    
    LOG.debug "repeatCommand: id=${id}, localKey=${localKey}, ipAddress=${ipAddress}, queue=${queue}"
    if (!id || !localKey || !ipAddress) { return result }

    // Synchronized required to stop multiple commands being sent
    // simultaneously which will overload the tuya TCP stack
    synchronized(queue) {
        for (i = 1; i <= repeat; i++) {
            try {
                LOG.debug "sending DPS command ${dps}"
                tuyaSendCommand(id, ipAddress, localKey, dps, 'CONTROL')
            } catch (e) {
                LOG.exception "tuya send exception", e
                increaseErrorCount(e.message)
                pauseExecution(250)
                increaseRetryCount()
                continue
            }

            result = queue.poll(timeoutSecs, TimeUnit.SECONDS)
            if (result) {
                LOG.info "received device ack"
                break
            } else {
                LOG.warn "command timeout (${i} of ${repeat})"
                increaseRetryCount()
            }
        }
    }

    return result
}

@Field private final Map LOG = [
    debug: { s -> if (settings.logEnable == true) { log.debug(s) } },
    info: { s -> log.info(s) },
    warn: { s -> log.warn(s) },
    error: { s -> log.error(s) },
    exception: { message, exception ->
        List<StackTraceElement> relevantEntries = exception.stackTrace.findAll { entry -> entry.className.startsWith("user_app") }
        Integer line = relevantEntries[0]?.lineNumber
        String method = relevantEntries[0]?.methodName
        log.error("${message}: ${exception} at line ${line} (${method})")
        if (settings.logEnable) {
            log.debug("App exception stack trace:\n${relevantEntries.join("\n")}")
        }
    }
]
/*
 * Tuya Protocol Functions (encode, decode, encryption along with byte array manipulation functions)
 *
 * Code in this section is licensed under the Eclipse Public License 1.0. The author is grateful to
 * Wim Vissers for his work on the Tuya OpenHAB add-on code at https://github.com/wvissers/openhab2-addons-tuya
 * which has been adapted from the original for sandboxed Groovy execution.
 */
Map tuyaDecode(byte[] buffer, String localKey) {
    Map result = [:]

//    LOG.debug "tuyaDecode: buffer=${buffer}"
    if (buffer.length < 24) {
        result.error = 'Packet too short (less than 24 bytes). Length: ' + buffer.length
        return result
    }

    long prefix = getUInt32(buffer, 0)
    if (prefix != 0x000055AA) {
        result.error = 'Prefix does not match: ' + String.format('%x', prefix)
        return result
    }

    result.sequenceNumber = getUInt32(buffer, 4)
    result.commandByte = getUInt32(buffer, 8)
    result.payloadSize = getUInt32(buffer, 12)

    long expectedCrc = getUInt32(buffer, (int) (16 + result.payloadSize - 8))
    long computedCrc = tuyaCrc32(copy(buffer, 0, (int) result.payloadSize + 8))
    if (computedCrc != expectedCrc) {
        result.error = 'CRC error. Expected: ' + expectedCrc + ', computed: ' + computedCrc
        return result
    }

    // Get the return code, 0 = success
    // This field is only present in messages from the devices
    // Absent in messages sent to device
    result.returnCode = getUInt32(buffer, 16) & 0xFFFFFF00

    // Get the payload
    // Adjust for status messages lacking a return code
    byte[] payload
    boolean status = false
    if (result.returnCode != 0) {
        payload = copy(buffer, 16, (int) (result.payloadSize - 8))
    } else if (result.commandByte == 8) { // STATUS
        status = true
        payload = copy(buffer, 16 + 3, (int) (result.payloadSize - 11))
    } else {
        payload = copy(buffer, 16 + 4, (int) (result.payloadSize - 12))
    }

    try {
        byte[] data = tuyaDecrypt(payload, localKey)
        result.text = status ? new String(data, 16, data.length - 16) : new String(data, 'UTF-8')
    } catch (e) {
        result.error = e
    }

//    LOG.debug "tuyaDecode: result=${result}"
    return result
}

byte[] tuyaEncode(String command, String input, String localKey, long seq = 0) {
    byte[] payload = tuyaEncrypt(input.getBytes('UTF-8'), localKey)

    // Check if we need an extended header, only for certain CommandTypes
    if (command != 'DP_QUERY') {
        byte[] buffer = new byte[payload.length + 15]
        fill(buffer, (byte) 0x00, 0, 15)
        copy(buffer, '3.3', 0)
        copy(buffer, payload, 15)
        payload = buffer
    }

    // Allocate buffer with room for payload + 24 bytes for
    // prefix, sequence, command, length, crc, and suffix
    byte[] buffer = new byte[payload.length + 24]

    // Add prefix, command and length.
    putUInt32(buffer, 0, 0x000055AA)
    putUInt32(buffer, 8, tuyaCommandByte(command))
    putUInt32(buffer, 12, payload.length + 8)

    // Optionally add sequence number.
    putUInt32(buffer, 4, seq)

    // Add payload, crc and suffix
    copy(buffer, payload, 16)
    byte[] crcbuf = new byte[payload.length + 16]
    copy(crcbuf, buffer, 0, payload.length + 16)
    putUInt32(buffer, payload.length + 16, tuyaCrc32(crcbuf))
    putUInt32(buffer, payload.length + 20, 0x0000AA55)

//    LOG.debug "tuyaEncode: buffer=${buffer}"
    return buffer
}

Boolean tuyaSendCommand(String devId, String ipAddress, String localKey, Map dps, String command) {
    if (!ipAddress || !localKey) { return false }

    byte[] output = tuyaEncode(
        command,
        JsonOutput.toJson([
            gwId: devId,
            devId: devId,
            t: Math.round(now() / 1000),
            dps: dps,
            uid: ''
        ]),
        localKey
    )

    interfaces.rawSocket.connect(ipAddress, 6668, byteInterface: true)
    interfaces.rawSocket.sendMessage(HexUtils.byteArrayToHexString(output))
    
//    LOG.debug "tuyaSendCommand: output=${output}"
    return true
}

private static byte[] copy(byte[] buffer, String source, int from) {
    return copy(buffer, source.bytes, from)
}

private static byte[] copy(byte[] buffer, byte[] source, int from) {
    for (int i = 0; i < source.length; i++) {
        buffer[i + from] = source[i]
    }
    return buffer
}

private static byte[] copy(byte[] source, int from, int length) {
    byte[] buffer = new byte[length]
    for (int i = 0; i < length; i++) {
        buffer[i] = source[i + from]
    }
    return buffer
}

private static byte[] copy(byte[] buffer, byte[] source, int from, int length) {
    for (int i = 0; i < length; i++) {
        buffer[i + from] = source[i]
    }
    return buffer
}

private static byte tuyaCommandByte(String command) {
    switch (command) {
        case 'CONTROL': return 7
        case 'STATUS': return 8
        case 'HEART_BEAT': return 9
        case 'DP_QUERY': return 10
    }
}

private static long tuyaCrc32(byte[] buffer) {
    long crc = 0xFFFFFFFFL
    for (byte b : buffer) {
        crc = ((crc >>> 8) & 0xFFFFFFFFL) ^ (tuyaCrc32Table[(int) ((crc ^ b) & 0xff)] & 0xFFFFFFFFL)
    }
    return ((crc & 0xFFFFFFFFL) ^ 0xFFFFFFFFL) & 0xFFFFFFFFL // return 0xFFFFFFFFL
}

private static byte[] tuyaDecrypt(byte[] payload, String secret) {
    SecretKeySpec key = new SecretKeySpec(secret.bytes, 'AES')
    Cipher cipher = Cipher.getInstance('AES/ECB/PKCS5Padding')
    cipher.init(Cipher.DECRYPT_MODE, key)
    return cipher.doFinal(payload)
}

private static byte[] tuyaEncrypt(byte[] payload, String secret) {
    SecretKeySpec key = new SecretKeySpec(secret.bytes, 'AES')
    Cipher cipher = Cipher.getInstance('AES/ECB/PKCS5Padding')
    cipher.init(Cipher.ENCRYPT_MODE, key)
    return cipher.doFinal(payload)
}

private static byte[] fill(byte[] buffer, byte fill, int from, int length) {
    for (int i = from; i < from + length; i++) {
        buffer[i] = fill
    }

    return buffer
}

private static long getUInt32(byte[] buffer, int start) {
    long result = 0
    for (int i = start; i < start + 4; i++) {
        result *= 256
        result += (buffer[i] & 0xff)
    }

    return result
}

private static void putUInt32(byte[] buffer, int start, long value) {
    long lv = value
    for (int i = 3; i >= 0; i--) {
        buffer[start + i] = (byte) (((lv & 0xFFFFFFFF) % 0x100) & 0xFF)
        lv /= 0x100
    }
}

@Field static final long[] tuyaCrc32Table = [ 0x00000000, 0x77073096, 0xEE0E612C, 0x990951BA, 0x076DC419, 0x706AF48F,
    0xE963A535, 0x9E6495A3, 0x0EDB8832, 0x79DCB8A4, 0xE0D5E91E, 0x97D2D988, 0x09B64C2B, 0x7EB17CBD, 0xE7B82D07,
    0x90BF1D91, 0x1DB71064, 0x6AB020F2, 0xF3B97148, 0x84BE41DE, 0x1ADAD47D, 0x6DDDE4EB, 0xF4D4B551, 0x83D385C7,
    0x136C9856, 0x646BA8C0, 0xFD62F97A, 0x8A65C9EC, 0x14015C4F, 0x63066CD9, 0xFA0F3D63, 0x8D080DF5, 0x3B6E20C8,
    0x4C69105E, 0xD56041E4, 0xA2677172, 0x3C03E4D1, 0x4B04D447, 0xD20D85FD, 0xA50AB56B, 0x35B5A8FA, 0x42B2986C,
    0xDBBBC9D6, 0xACBCF940, 0x32D86CE3, 0x45DF5C75, 0xDCD60DCF, 0xABD13D59, 0x26D930AC, 0x51DE003A, 0xC8D75180,
    0xBFD06116, 0x21B4F4B5, 0x56B3C423, 0xCFBA9599, 0xB8BDA50F, 0x2802B89E, 0x5F058808, 0xC60CD9B2, 0xB10BE924,
    0x2F6F7C87, 0x58684C11, 0xC1611DAB, 0xB6662D3D, 0x76DC4190, 0x01DB7106, 0x98D220BC, 0xEFD5102A, 0x71B18589,
    0x06B6B51F, 0x9FBFE4A5, 0xE8B8D433, 0x7807C9A2, 0x0F00F934, 0x9609A88E, 0xE10E9818, 0x7F6A0DBB, 0x086D3D2D,
    0x91646C97, 0xE6635C01, 0x6B6B51F4, 0x1C6C6162, 0x856530D8, 0xF262004E, 0x6C0695ED, 0x1B01A57B, 0x8208F4C1,
    0xF50FC457, 0x65B0D9C6, 0x12B7E950, 0x8BBEB8EA, 0xFCB9887C, 0x62DD1DDF, 0x15DA2D49, 0x8CD37CF3, 0xFBD44C65,
    0x4DB26158, 0x3AB551CE, 0xA3BC0074, 0xD4BB30E2, 0x4ADFA541, 0x3DD895D7, 0xA4D1C46D, 0xD3D6F4FB, 0x4369E96A,
    0x346ED9FC, 0xAD678846, 0xDA60B8D0, 0x44042D73, 0x33031DE5, 0xAA0A4C5F, 0xDD0D7CC9, 0x5005713C, 0x270241AA,
    0xBE0B1010, 0xC90C2086, 0x5768B525, 0x206F85B3, 0xB966D409, 0xCE61E49F, 0x5EDEF90E, 0x29D9C998, 0xB0D09822,
    0xC7D7A8B4, 0x59B33D17, 0x2EB40D81, 0xB7BD5C3B, 0xC0BA6CAD, 0xEDB88320, 0x9ABFB3B6, 0x03B6E20C, 0x74B1D29A,
    0xEAD54739, 0x9DD277AF, 0x04DB2615, 0x73DC1683, 0xE3630B12, 0x94643B84, 0x0D6D6A3E, 0x7A6A5AA8, 0xE40ECF0B,
    0x9309FF9D, 0x0A00AE27, 0x7D079EB1, 0xF00F9344, 0x8708A3D2, 0x1E01F268, 0x6906C2FE, 0xF762575D, 0x806567CB,
    0x196C3671, 0x6E6B06E7, 0xFED41B76, 0x89D32BE0, 0x10DA7A5A, 0x67DD4ACC, 0xF9B9DF6F, 0x8EBEEFF9, 0x17B7BE43,
    0x60B08ED5, 0xD6D6A3E8, 0xA1D1937E, 0x38D8C2C4, 0x4FDFF252, 0xD1BB67F1, 0xA6BC5767, 0x3FB506DD, 0x48B2364B,
    0xD80D2BDA, 0xAF0A1B4C, 0x36034AF6, 0x41047A60, 0xDF60EFC3, 0xA867DF55, 0x316E8EEF, 0x4669BE79, 0xCB61B38C,
    0xBC66831A, 0x256FD2A0, 0x5268E236, 0xCC0C7795, 0xBB0B4703, 0x220216B9, 0x5505262F, 0xC5BA3BBE, 0xB2BD0B28,
    0x2BB45A92, 0x5CB36A04, 0xC2D7FFA7, 0xB5D0CF31, 0x2CD99E8B, 0x5BDEAE1D, 0x9B64C2B0, 0xEC63F226, 0x756AA39C,
    0x026D930A, 0x9C0906A9, 0xEB0E363F, 0x72076785, 0x05005713, 0x95BF4A82, 0xE2B87A14, 0x7BB12BAE, 0x0CB61B38,
    0x92D28E9B, 0xE5D5BE0D, 0x7CDCEFB7, 0x0BDBDF21, 0x86D3D2D4, 0xF1D4E242, 0x68DDB3F8, 0x1FDA836E, 0x81BE16CD,
    0xF6B9265B, 0x6FB077E1, 0x18B74777, 0x88085AE6, 0xFF0F6A70, 0x66063BCA, 0x11010B5C, 0x8F659EFF, 0xF862AE69,
    0x616BFFD3, 0x166CCF45, 0xA00AE278, 0xD70DD2EE, 0x4E048354, 0x3903B3C2, 0xA7672661, 0xD06016F7, 0x4969474D,
    0x3E6E77DB, 0xAED16A4A, 0xD9D65ADC, 0x40DF0B66, 0x37D83BF0, 0xA9BCAE53, 0xDEBB9EC5, 0x47B2CF7F, 0x30B5FFE9,
    0xBDBDF21C, 0xCABAC28A, 0x53B39330, 0x24B4A3A6, 0xBAD03605, 0xCDD70693, 0x54DE5729, 0x23D967BF, 0xB3667A2E,
    0xC4614AB8, 0x5D681B02, 0x2A6F2B94, 0xB40BBE37, 0xC30C8EA1, 0x5A05DF1B, 0x2D02EF8D ]
/* End of Tuya Protocol Functions */
