/**
*  NHL Goal Notification
*
*  Copyright 2017 Eric Luttmann
*
*  Based in part on ideas from "NHL Goal Light" by Patrick Mjoen
*  
*  Description:
*  This app was built to provide a solution similar to Budweiser Red Light.
*
*  When your hockey team scores, the app can be setup to control lights, switches,
*  play your teams goal scoring song, and send notifications.
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
*/

include 'asynchttp_v1'
import groovy.json.JsonSlurper
import groovy.json.JsonOutput

definition(
    name: "NHL Goal Notification",
    namespace: "ejluttmann",
    author: "Eric Luttmann",
    description: "Get notified when your hockey team scores!",
    category: "My Apps",
    iconUrl: "https://cloud.githubusercontent.com/assets/2913371/22167524/3390bf68-df24-11e6-94c6-b099063842df.png",
    iconX2Url: "https://cloud.githubusercontent.com/assets/2913371/22167524/3390bf68-df24-11e6-94c6-b099063842df.png",
    iconX3Url: "https://cloud.githubusercontent.com/assets/2913371/22167524/3390bf68-df24-11e6-94c6-b099063842df.png")


preferences {
    section("NHL Team") {
        input "team", "enum", title: "Team Selection", required: true, displayDuringSetup: true, options: ["Avalanche","Blackhawks","Blue Jackets","Blues","Bruins","Canadiens","Canucks","Capitals","Coyotes","Devils","Ducks","Flames","Flyers","Hurricanes","Islanders","Jets","Kings","Lighting","Maple Leafs","Oilers","Panthers","Penguins","Predators","Rangers","Red Wings","Sabres","Senators","Sharks","Stars","Wild"]
    }

    section("Momentary Buttons"){
        input "buttons", "capability.momentary", title: "Devices Selection", required: false, multiple: true, displayDuringSetup: true
    }

    section("Switches To Turn On"){
        input "switches", "capability.switch", title: "Devices Selection", required: false, multiple: true, displayDuringSetup: true
        input "switchOnFor", "enum", title: "Turn Off After (seconds)", required: false, defaultValue: "5", multiple: false, displayDuringSetup: true, options: ["1","2","3","4","5","6","7","8","9","10"]
    }

    section("Flash Switches"){
        input "flashes", "capability.switch", title: "These lights", multiple: true, required: false
        input "numFlashes", "number", title: "This number of times (default 3)", required: false, defaultValue: "3"
    }

    section ("Speakers"){
        input "sound", "capability.musicPlayer", title: "Music Devices Selection", required: false, displayDuringSetup: true
        input "soundDuration", "enum", title: "Sound duration(seconds)", required: false, defaultValue: "5", multiple: false, displayDuringSetup: true, options: ["1","2","3","4","5","6","7","8","9","10"]
    }

    section("Sirens"){
        input "sirens", "capability.alarm", title: "Which?", required: false, multiple: true
    }

    section( "Notifications" ) {
        input "sendGoalMessage", "bool", title: "Send goal notifications?", defaultValue: "true", displayDuringSetup: true, required:false
        input "sendGameDayMessage", "bool", title: "Send game day status notifications?", defaultValue: "false", displayDuringSetup: true, required:false
        input "sendPushMessage", "bool", title: "Send a push notification?", defaultValue: "false", displayDuringSetup: true, required:false
        input "textPhone", "phone", title: "Send a Text Message?", required: false
    }

    section("Only during this time (default 8:00 AM to 10:00 PM)") {
        input "startTime", "time", title: "Start Time?", required: false
        input "endTime", "time", title: "End Time?", required: false
    }
}

def installed() {
    log.debug "Installed with settings: ${settings}"

    initialize()
}

def updated() {
    log.debug "Updated with settings: ${settings}"

    unschedule()
    unsubscribe()
    initialize()
}

def initialize() {
    state.NHL_URL = "http://statsapi.web.nhl.com"
    state.NHL_API_URL = "${state.NHL_URL}/api/v1"

    state.GAME_STATUS_SCHEDULED            = '1'
    state.GAME_STATUS_PREGAME              = '2'
    state.GAME_STATUS_IN_PROGRESS          = '3'
    state.GAME_STATUS_IN_PROGRESS_CRITICAL = '4'
    state.GAME_STATUS_UNKNOWN              = '5'
    state.GAME_STATUS_FINAL6               = '6'
    state.GAME_STATUS_FINAL7               = '7'

    state.currentGameStatus = state.GAME_STATUS_UNKNOWN
    state.gameStatus = state.GAME_STATUS_UNKNOWN
    state.teamScore = 0
    state.opponentScore = 0
    state.Team = new org.json.JSONObject()
    state.Game = new org.json.JSONObject()
    state.LiveLInk = ""

    getTeam()

    unschedule()
    schedule(timeTodayAfter(new Date(), "1:00", location.timeZone), initialize)
}

def getTeamHandler(resp, data) {
    def found = false

    // get the status code of the response
    //log.debug "response status code: ${resp.status}"
    //log.debug "data passed to response handler: $data"

    if (resp.status == 200) {
        def slurper = new groovy.json.JsonSlurper()
        def result = slurper.parseText(resp.getData())

        for (rec in result.teams) {
            if (settings.team == rec.teamName) {
                state.Team = rec
                log.debug "Found info on team ${state.Team.teamName}, id=${state.Team.id}"
                found = true
                break
            }
        } 

    }
    else {
        log.debug "Request Failed To Return Data"
    }

    if (found == false) {
        log.debug "Unable to locate info on team ${state.Team.teamName}, trying again in 30 seconds..."
        runIn(30,getTeam)
    }
    else {
        runIn(1, checkForGame)
    }
}

def getTeam() {
    def params = [uri: "${state.NHL_API_URL}/teams"] 
    log.debug "Setup for team ${settings.team}"
    asynchttp_v1.get(getTeamHandler, params)
}

def checkForGameHandler(resp, data) {
    // get the status code of the response
    //log.debug "response status code: ${resp.status}"
    //log.debug "data passed to response handler: $data"

    // default status to unknown
    state.currentGameStatus = state.GAME_STATUS_UNKNOWN
    state.Game = null

    if (resp.status == 200) {
        def slurper = new groovy.json.JsonSlurper()
        def result = slurper.parseText(resp.getData())
        def isGameDay = false
        def runDelay = 30

        for (date in result.dates) {
            for (game in date.games)
            {
                isGameDay = true

                state.Game = game
                state.currentGameStatus = game.status.statusCode

                switch (state.currentGameStatus) {
                    case state.GAME_STATUS_SCHEDULED:
                    log.debug "${game.teams.away.team.name} vs ${game.teams.home.team.name}  - scheduled for today!"
                    runDelay = (5 * 60)
                    break

                    case state.GAME_STATUS_PREGAME:
                    log.debug "${game.teams.away.team.name} vs ${game.teams.home.team.name} - pregame!"
                    runDelay = 30
                    break

                    case state.GAME_STATUS_IN_PROGRESS:
                    case state.GAME_STATUS_IN_PROGRESS_CRITICAL:
                    log.debug "${game.teams.away.team.name} vs ${game.teams.home.team.name} - game is on!!!"

                    def teamScore = getTeamScore(game.teams)
                    def opponentScore = getOpponentScore(game.teams)
                    
                    runDelay = 5
 
                    // reset left over old score values
                    if (state.teamScore > teamScore || state.opponentScore > opponentScore) {
                        log.debug "Reset scores"
                        state.teamScore = 0
                        state.opponentScore = 0
                    }

                    if (teamScore > state.teamScore) {
                        state.teamScore = teamScore
                        runIn(0, teamGoalScored)
                    } else if (opponentScore > state.opponentScore) {
                        state.teamScore = teamScore
                        runIn(0, opponentGoalScored)
                    } else {
                        log.debug "No change in scores"
                    }
                    break

                    case state.GAME_STATUS_FINAL6:
                    case state.GAME_STATUS_FINAL7:
                    log.debug "${game.teams.away.team.name} vs ${game.teams.home.team.name} - game is over!"
                    runDelay = (5 * 60)
                    break

                    case state.GAME_STATUS_UNKNOWN:
                    default:
                        log.debug "${game.teams.away.team.name} vs ${game.teams.home.team.name} game is unknown!"
                    runDelay = 30
                    break
                }

                if (state.gameStatus != state.currentGameStatus) {
                    runIn(0, triggerStatusNotifications)
                }

                // break out of loop
                break
            }
        }

        if (isGameDay) {
            log.debug "Checking game status again in ${runDelay} seconds..."
            runIn(runDelay, checkForGame)
        } else {
            log.debug "Not a game day."
        }

    } else {
        log.debug "Request Failed To Return Data"
        runDelay = 30
        log.debug "Trying again in ${runDelay} seconds..."
        runIn(runDelay, checkForGame)
    }
}

def checkForGame() {
    def todaysDate = new Date().format('yyyy-MM-dd',location.timeZone)
    //def todaysDate = "2017-01-24"

    def params = [uri: "${state.NHL_API_URL}/schedule?teamId=${state.Team.id}&date=${todaysDate}"] 
    log.debug "Requesting ${settings.team} game schedule for ${todaysDate}"
    asynchttp_v1.get(checkForGameHandler, params)
}

def getTeamScore(teams) {
    return getScore(teams, false)
}

def getOpponentScore(teams) {
    return getScore(teams, true)
}

def getScore(teams, opponent) {
    log.debug "Getting current score for ${settings.team}"

    def score = 0

    if (state.Team.id == teams.away.team.id) {
        if (opponent) {
        	score = teams.home.score
        } else {
        	score = teams.away.score
        }
    } else if (state.Team.id == teams.home.team.id) {
        if (opponent) {
        	score = teams.away.score
        } else {
	        score = teams.home.score
        }
    }

    if (opponent) {
        log.debug "found opponent score ${score}"
    } else {
        log.debug "found team score ${score}"
    }
    
    return score
}

def teamGoalScored() {
    log.debug "GGGOOOAAALLL!!!"

    triggerButtons()
    triggerSwitchesOn()
    triggerFlashes()
    triggerGoalNotifications()
}

def opponentGoalScored() {
    log.debug "BOOOOOOO!!!"
}

def triggerButtons() {
    try {
        buttons.push()
        log.debug "Butttons pushed"
    } catch(ex) {
        log.debug "Error pushing buttons"
    }
}

def triggerSwitchesOn() {
    try {
        def switchOnFor = switchOnFor ?: 3
        
        log.debug "Switches on"
        switches.on()
        runIn(switchOnFor, triggerSwitchesOff)
    } catch(ex) {
        log.debug "Error turning on switches"
    }
}

def triggerSwitchesOff() {
    try {
        log.debug "Switches off"
        switches.off()
    } catch(ex) {
        log.debug "Error turning off switches"
    }
}

def triggerFlashes() {
    try {
        runIn(0, flashLights)
    } catch(ex) {
        log.debug "Error running flashing lights"
    }
}

def flashLights() {
    try {
        def doFlash = true
        def onFor = onFor ?: 1000
        def offFor = offFor ?: 1000
        def numFlashes = numFlashes ?: 3

        log.debug "LAST ACTIVATED IS: ${state.lastActivated}"
        if (state.lastActivated) {
            def elapsed = now() - state.lastActivated
            def sequenceTime = (numFlashes + 1) * (onFor + offFor)
            doFlash = elapsed > sequenceTime
            log.debug "DO FLASH: $doFlash, ELAPSED: $elapsed, LAST ACTIVATED: ${state.lastActivated}"
        }

        if (doFlash) {
            log.debug "FLASHING $numFlashes times"
            state.lastActivated = now()
            log.debug "LAST ACTIVATED SET TO: ${state.lastActivated}"
            def initialActionOn = switches.collect{it.currentSwitch != "on"}
            def delay = 0L
            numFlashes.times {
                log.trace "Switch on after  $delay msec"
                switches.eachWithIndex {s, i ->
                    if (initialActionOn[i]) {
                        s.on(delay: delay)
                    }
                    else {
                        s.off(delay:delay)
                    }
                }
                delay += onFor
                log.trace "Switch off after $delay msec"
                switches.eachWithIndex {s, i ->
                    if (initialActionOn[i]) {
                        s.off(delay: delay)
                    }
                    else {
                        s.on(delay:delay)
                    }
                }
                delay += offFor
            }
        }
    } catch(ex) {
        log.debug "Error Flashing Lights"
    }
}

def triggerGoalNotifications() {
    if (sendGoalMessage) {
        def game = state.Game
        def msg = null

        if (game) {           
            def goals = getTeamScore(game.teams)

            if (goals == 1) {
                msg = "${settings.team}'s scored first goal!"
            } else {
                msg = "${settings.team}'s scored ${goals} goals!"
            }
        } else {
            msg = "${settings.team} just Scored!"
        }

        triggerNotifications(msg)
    }
}

def triggerStatusNotifications() {
    if (sendGameDayMessage) {
        def game = state.Game
        def msg = null
        def msg2 = null

        if (game) {
            switch (state.currentGameStatus) {
                case state.GAME_STATUS_SCHEDULED:
                msg = "${game.teams.away.team.name} vs ${game.teams.home.team.name} scheduled for today!"
                break

                case state.GAME_STATUS_PREGAME:
                msg = "Pregame for ${game.teams.away.team.name} vs ${game.teams.home.team.name} has begun!"
                break

                case state.GAME_STATUS_IN_PROGRESS:
                case state.GAME_STATUS_IN_PROGRESS_CRITICAL:
                msg = "${game.teams.away.team.name} vs ${game.teams.home.team.name} is now in progress!"
                break

                case state.GAME_STATUS_FINAL6:
                case state.GAME_STATUS_FINAL7:
                msg = "Final Score:\n${game.teams.away.team.name} ${game.teams.away.score}\n${game.teams.home.team.name} ${game.teams.home.score}"

                if (getTeamScore(game.teams) > getOpponentScore(game.teams)) {
                    msg2 = "${settings.team} win!!!"
                } else if (getTeamScore(game.teams) < getOpponentScore(game.teams)) {
                    msg2 = "${settings.team} lost"
                } else {
                    msg2 = "Tie game!"
                }
                break

                case state.GAME_STATUS_UNKNOWN:
                default:
                    break
            }

            if (triggerNotifications(msg)) {
                state.gameStatus = state.currentGameStatus

                if (msg2) {
                    triggerNotifications(msg2)
                }
            }
        } else {
            log.debug( "invalid game object")
        }
    }
    else {
        // just set old status, no messages
        state.gameStatus = state.currentGameStatus    	    	
    }
}

def triggerNotifications(msg) {
    try {
        def start = timeToday(startTime?: "8:00", location.timeZone)
        def end = timeToday(endTime ?: "22:00", location.timeZone)

        if (now() >= start.time && now() <= end.time)
        {
            if (msg == null) {
                log.debug( "No message to send" )
            } else {
                if ( sendPushMessage == true ) {
                    log.debug( "Sending push message" )
                    log.debug( "msg: ${msg}" )
                    sendPush( msg )
                }

                if ( textPhone ) {
                    log.debug( "Sending text message to: ${textPhone}" )
                    log.debug( "msg: ${msg}" )
                    sendSms( textPhone, msg )
                }

                return true
            }
        } else {
            def startStamp = start.format('yyyy-MM-dd HH:MM:SS',location.timeZone)
            def endStamp = end.format('yyyy-MM-dd HH:MM:SS',location.timeZone)
            log.debug( "Ignore message, time of day not between ${startStamp} and ${endStamp}." )
        }
    } catch(ex) {
        log.debug "Error sending notifications"
    }

    return false
}
