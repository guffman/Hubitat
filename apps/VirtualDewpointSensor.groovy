    //Sensor validity tests
    dptStr = String.format("%.1f", dewpointF) + "°F"
    dptStr = dptStr.trim()
    
    if ((result > lowClamp) && (result < highClamp)) {
		state.clamped = false
        updateAppLabel("${dptStr}", "green")
        dewpointDev.setTemperature(dewpointF)
    } else {
		state.clamped = true
        if (result < lowClamp) {
            log.warn "Dewpoint clamped: calculated value ${dptStr} below ${lowClamp}"
            updateAppLabel("Below Low Limit - Clamped", "orange", dptStr)
        } else if (result > highClamp) {
            log.warn "Dewpoint clamped: calculated value ${dptStr} above ${highClamp}"
            updateAppLabel("Above High Limit - Clamped", "orange", dptStr)
        }
    }
}

def f2c (degF) {
    def degC = 5 * ((degF-32)/9)
    return degC
}

def c2f (degC) {
    def degF = 32 + (9*(degC/5))
    return degF
}

def dpC (T, RH) {
    def Td = 243.04 * (Math.log(RH/100)+((17.625*T)/(243.04+T)))/(17.625-Math.log(RH/100)-((17.625*T)/(243.04+T)))
    return Td                       
}

def tempHandler(evt) {
    //log.info "In tempHandler"
    calcDewpoint()
}

def humidHandler(evt) {
    //log.info "In humidHandler"
    calcDewpoint()
}

def updateAppLabel(textStr, textColor, def textPrefix=null) {
    def str = (textPrefix != null) ? """<span style='color:$textColor'> ($textPrefix $textStr)</span>""" : """<span style='color:$textColor'> ($textStr)</span>"""
    app.updateLabel(state.label + str)
}
