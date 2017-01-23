/**
 *  NHL Goal Notification
 *
 *  Copyright 2017 Eric Luttmann
 *
 *  Based in part on "NHL Goal Light" by Patrick Mjoen
 *  
 *  Description:
 *  This app was built to provide a solution similar to Budweiser Red Light.
 *
 *  When your hockey team scores, the app can be setup to control lights, switches
 *  and play your teams goal scoring song.
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
    description: "Get notified when your hockey team scores by controlling lights, switches and playing your teams goal scoring song.",
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
        input "sendPushMessage", "bool", title: "Send a push notification?", defaultValue: "false", displayDuringSetup: true, required:false
        input "textPhone", "phone", title: "Send a Text Message?", required: false
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
    state.test_goal = false
    
    state.CHECK_NOW				= "now"
    state.CHECK_SCHEDULED 		= "scheduled"
    state.CHECK_PREGAME	 		= "pregame"
    state.CHECK_POSTGAME	 	= "postgame"
    state.CHECK_NEXT_DAY	 	= "nextday"
    
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
    state.OldScore = 0
    state.Team = new org.json.JSONObject()
    state.LiveLInk = ""
    
    getTeam()
}

def checkIfInSeason() {
    def month = new Date().getMonth() + 1
    if (month==7 || month==8 || month==9)
        return false
    else
        return true    
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
	          log.debug "Found ${state.Team.teamName}, id=${state.Team.id}"
              found = true
              break
           }
        } 

    }
    else {
		log.debug "Request Failed To Return Data"
	}
    
    if (found == false) {
       runIn(30,getTeam)
    }
    else {
       checkGameSchedule()
    }
}

def getTeam() {
    def todaysDate = new Date().format('yyyyMMdd')
    def params = [uri: "${state.NHL_API_URL}/teams"] 
    log.debug "Requesting teams list"
    asynchttp_v1.get(getTeamHandler, params)
}

def checkGameSchedule() {
    log.debug "Checking to determine if it is hockey season..."
    if (checkIfInSeason() == true) {
	    def nextTime = 0
        
        log.debug "It is hockey season!"
        
        switch (state.currentGameStatus) {
            case state.GAME_STATUS_SCHEDULED:
                nextTime = 5*60
                break
            case state.GAME_STATUS_PREGAME:
                nextTime = 30
                break
            case state.GAME_STATUS_IN_PROGRESS:
            case state.GAME_STATUS_IN_PROGRESS_CRITICAL:
            case state.GAME_STATUS_UNKNOWN:
                nextTime = 0
                break
            default:
                nextTime = 60*60
        }
        
        log.debug "Check game info again in ${nextTime} seconds"
    	runIn(nextTime, checkForGame)
    } else {
        def nextDay = new Date() + 1
        def stamp = nextDay.format('yyyy-M-d hh:mm:ss',TimeZone.getTimeZone('GMT'))
        log.debug "It is NOT hockey season, checking again on ${stamp}"
        state.currentGameStatus = state.GAME_STATUS_UNKNOWN
    	runOnce(nextDay, checkGameSchedule)
    }
    
    if (state.test_goal == true) {
        runIn(0, goalScored)
    }
    
}

def checkForGameHandler(resp, data) {
    def isGameDay = false
    def isGameOn = false
    
    // get the status code of the response
    //log.debug "response status code: ${resp.status}"
    //log.debug "data passed to response handler: $data"
    
    if (resp.status == 200) {
        def slurper = new groovy.json.JsonSlurper()
        def result = slurper.parseText(resp.getData())

        for (date in result.dates) {
           for (game in date.games)
           {
//               log.debug "raw response: ${game.dump()}"
               isGameDay = true
               
               state.currentGameStatus = game.status.statusCode
               
               if (state.currentGameStatus == state.GAME_STATUS_IN_PROGRESS || statusCode == state.GAME_STATUS_IN_PROGRESS_CRITICAL) {
                   log.debug "${game.teams.away.team.name} vs ${game.teams.home.team.name} game is on!!!"
                   isGameOn = true
                   state.LiveLInk = game.link
               } else if (state.currentGameStatus == state.GAME_STATUS_SCHEDULED) {
                   log.debug "${game.teams.away.team.name} vs ${game.teams.home.team.name} game is scheduled for today!"
               } else if (state.currentGameStatus == state.GAME_STATUS_PREGAME) {
                   log.debug "${game.teams.away.team.name} vs ${game.teams.home.team.name} game is close!"
               } else if (state.currentGameStatus == state.GAME_STATUS_FINAL6 || statusCode == state.GAME_STATUS_FINAL7) {
                   log.debug "${game.teams.away.team.name} vs ${game.teams.home.team.name} game is over!"
                   isGameDay = false // game is done for the day
               } else {
                   log.debug "${game.teams.away.team.name} vs ${game.teams.home.team.name} game is unknown!"
               }
               break
           }
        }
        
        if (isGameDay == false) {
            log.debug "No game today, set status to Final"
            state.currentGameStatus = state.GAME_STATUS_FINAL7
        }
    }
    else {
		log.debug "Request Failed To Return Data"
        state.currentGameStatus = state.GAME_STATUS_UNKNOWN
	}
        
    if (isGameDay == true) {
        log.debug "It is game day!!!"
        
        if (isGameOn == true) {
           log.debug "Game is on!!!"
           gameInProcess();
        } else {
            checkGameSchedule()
        }
    } else {
        log.debug "No game today."
        checkGameSchedule()
    }
}

def checkForGame() {
    log.debug "Checking game day..."
    def todaysDate = new Date().format('yyyy-MM-dd')
    //def todaysDate = "2017-01-20"
    def params = [uri: "${state.NHL_API_URL}/schedule?teamId=${state.Team.id}&date=${todaysDate}"] 
    log.debug "Requesting ${settings.team} game schedule for ${todaysDate}"
    asynchttp_v1.get(checkForGameHandler, params)
}

def getGoals(live) {
    log.debug "get current score for ${settings.team}"
    
    def goals = 0
    
    if (state.Team.id == live.linescore.teams.away.team.id) {
        log.debug "asway team goals"
        goals = live.linescore.teams.away.goals
    } else if (state.Team.id == live.linescore.teams.home.team.id) {
        log.debug "home team goals"
        goals = live.linescore.teams.home.goals
    }
    
    log.debug "found score ${goals}"
    return goals
}

def gameInProcessHandler(resp, data) {
	def isGameOn = false
    
    // get the status code of the response
//    log.debug "response status code: ${resp.status}"
//    log.debug "data passed to response handler: $data"
    
    try {
        if (resp.status == 200) {
            def slurper = new groovy.json.JsonSlurper()
            def result = slurper.parseText(resp.getData())

            state.LiveLInk = result.link
            def game = result.gameData
            def live = result.liveData

            state.currentGameStatus = game.status.statusCode
            if (state.currentGameStatus == state.GAME_STATUS_IN_PROGRESS || statusCode == state.GAME_STATUS_IN_PROGRESS_CRITICAL) {
                log.debug "${game.teams.away.name} vs ${game.teams.home.name} game is on!!!"
                isGameOn = true

                def curScore = getGoals(live)

                // reset left over old score value
                if (state.OldScore > curScore) {
                    log.debug "Reset scores"
                    state.OldScore = 0
                }

                if (curScore > state.OldScore) {
                    runIn(0, goalScored)
                    state.OldScore = curScore
                } else {
                    log.debug "No change in score"
                }

            } else {
                log.debug "${game.teams.away.name} vs ${game.teams.home.name} game is done."
            }
        }
        else {
            log.debug "Request Failed To Return Data"
        }
    } catch (e) {
        log.debug "Exception Error while processing data!"
    }
    
    if (isGameOn == true) {
//        gameInProcess()
       	runIn(0, gameInProcess)
    } else {
        checkGameSchedule()
    }
}

def gameInProcess() {
    log.debug "Game in process..."
    def params = [uri: "${state.NHL_URL}${state.LiveLInk}"] 
    log.debug "Requesting ${settings.team} game data..."
    asynchttp_v1.get(gameInProcessHandler, params)
}

def goalScored() {
    log.debug "GGGOOOAAALLL!!!"
    
    triggerButtons()
    triggerSwitchesOn()
    triggerFlashes()
    triggerNotifications()
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
        switches.on()
        runIn(3, triggerSwitchesOff)
        log.debug "Switches on"
    } catch(ex) {
    	log.debug "Error turning on switches"
    }
 }

def triggerSwitchesOff() {
    try {
        switches.off()
        log.debug "Switches off"
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

def triggerNotifications() {
    try {
        def msg = "${settings.team} Scored!"
        
        if ( sendPushMessage == true ) {
            log.debug( "Sending push message" )
            sendPush( msg )
        }

        if ( textPhone ) {
            log.debug( "Sending text message" )
            sendSms( textPhone, msg )
        }
    } catch(ex) {
    	log.debug "Error sending notifications"
    }
 }
