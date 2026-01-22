#include "../include/StompProtocol.h"
#include "../include/event.h"
#include <sstream>
#include <iostream>
#include <fstream>
#include <algorithm>
#include <vector>

using namespace std;

// --- Helper Functions ---
string trim(const string& str) {
    size_t first = str.find_first_not_of(" \t\r\n");
    if (string::npos == first) return "";
    size_t last = str.find_last_not_of(" \t\r\n");
    return str.substr(first, (last - first + 1));
}

// --- Constructor ---
StompProtocol::StompProtocol() : 
    isLoggedIn(false), 
    loginError(false), 
    logoutCompleted(false), 
    subCounter(0), 
    receiptCounter(0),
    loginMutex(),           
    loginCV(),              
    gameToSubId(),          
    subIdToGame(),          
    receiptToActions(),     
    game_to_user_events()   
{}

// --- Getters/Setters ---
bool StompProtocol::getIsLoggedIn() const { return isLoggedIn; }
void StompProtocol::setIsLoggedIn(const bool status){ isLoggedIn=status; }

// --- Wait Functions ---
bool StompProtocol::waitForLogin() {
    std::unique_lock<std::mutex> lock(loginMutex);
    loginCV.wait(lock, [this] {
        return isLoggedIn || loginError; 
    }); 
    return isLoggedIn;
}

bool StompProtocol::waitForLogout() {
    std::unique_lock<std::mutex> lock(loginMutex);
    loginCV.wait(lock, [this] {
        return logoutCompleted;
    });
    return true; 
}

// --- Parsing User Command ---
string StompProtocol::parseUserCommand(string input, string& userName) {
    stringstream userInputSS(input);
    string command;
    userInputSS >> command;

    if (command == "join") {
        string gameName; userInputSS >> gameName;
        if (gameName.empty()){
            std::cout << "Empty game name " << std::endl;
            return "";
        }
        return createSubscribeFrame(gameName);
    } 
    else if (command == "exit") {
        string gameName; userInputSS >> gameName;
        return createUnsubscribeFrame(gameName);
    }
else if (command == "report") {
        string fileName; 
        userInputSS >> fileName;
        
        // 1. Parse the file to get the events and team names
        names_and_events report_data = parseEventsFile("data/" + fileName);

        // 2. Construct the game name to check against subscriptions
        // The convention is "TeamA_TeamB"
        std::string gameName = report_data.team_a_name + "_" + report_data.team_b_name;

        // 3. Check if we are subscribed to this game
        if (gameToSubId.find(gameName) == gameToSubId.end()) {
            std::cout << "Error: You cannot send a report because you are not subscribed to " << gameName << std::endl;
            return ""; // Return empty string so nothing is sent to the server
        }

        std::cout << "sending report... \n" << std::endl;

        // 4. If subscribed, generate the frames
        string allFrames = "";
        for (const auto& event : report_data.events) {
            allFrames += createSendFrame(event, userName, fileName);
        }
        return allFrames;
    }

    else if (command == "logout") return createDisconnectFrame();
    else if (command == "summary") {
        std::cout << "generating summary... " << std::endl;
        string gameName; userInputSS >> gameName;
        string reporterUserName; userInputSS >> reporterUserName;
        string fileName; userInputSS >> fileName;
        generateSummary(gameName, reporterUserName, fileName);
        return ""; 
    }
    
    return "error"; 
}

// --- SERVER FRAME PROCESSING (THE FIX IS HERE) ---
string StompProtocol::processServerFrame(string frame) {
    stringstream frameSS(frame);
    string command;
    getline(frameSS, command);

    // 1. Handle CONNECTED
    if (command == "CONNECTED") {
        lock_guard<mutex> lock(loginMutex);
        isLoggedIn = true;
        loginCV.notify_all();
        return "Login successful \n";
    }

    // 2. Handle RECEIPT
    else if (command == "RECEIPT") {
        string receiptHeader; getline(frameSS, receiptHeader);
        if (!receiptHeader.empty() && receiptHeader.back() == '\r') receiptHeader.pop_back();

        size_t colon = receiptHeader.find(':');
        if (colon != string::npos) {
            int rId = stoi(receiptHeader.substr(colon + 1));
            if (receiptToActions.count(rId)) {
                string action = receiptToActions[rId];
                receiptToActions.erase(rId);

                if (action == "logout") {
                    {
                        lock_guard<mutex> lock(loginMutex);
                        isLoggedIn = false;     
                        logoutCompleted = true; 
                    }
                    loginCV.notify_all(); 
                    return "logout"; 
                }
                return action;
            }
        }
        return "";
    }

    // 3. Handle ERROR
    else if (command == "ERROR") {
        lock_guard<mutex> lock(loginMutex);
        loginError = true;
        isLoggedIn = false;
        loginCV.notify_all();
        return "Error from server: " + frame;
    }

    // 4. Handle MESSAGE
    else if (command == "MESSAGE") {
        std::map<string, string> headers;
        string line;

        // A. Parse Headers
        while (getline(frameSS, line) && !trim(line).empty()) {
            size_t colonPos = line.find(':');
            if (colonPos != string::npos) {
                string key = trim(line.substr(0, colonPos));
                string value = trim(line.substr(colonPos + 1));
                headers[key] = value;
            }
        }

        int subId = (headers.count("subscription")) ? stoi(headers["subscription"]) : -1;
        
        // B. Parse Body MANUALLY (Since Event(string) constructor is empty)
        string team_a_name, team_b_name, event_name, description;
        int time_val = 0;
        string senderUserName = "";
        std::map<string, string> game_updates;
        std::map<string, string> team_a_updates;
        std::map<string, string> team_b_updates;
        
        // Parsing state
        string current_section = ""; // "", "game", "team_a", "team_b", "desc"

        while (getline(frameSS, line)) {
            if (!line.empty() && line.back() == '\r') line.pop_back(); // Remove \r
            
            // Check for Main Keys
            if (line.find("user: ") == 0) {
                senderUserName = line.substr(6);
            }
            else if (line.find("team a: ") == 0) {
                team_a_name = line.substr(8);
            }
            else if (line.find("team b: ") == 0) {
                team_b_name = line.substr(8);
            }
            else if (line.find("event name: ") == 0) {
                event_name = line.substr(12);
            }
            else if (line.find("time: ") == 0) {
                try { time_val = stoi(line.substr(6)); } catch (...) { time_val = 0; }
            }
            // Check for Section Headers
            else if (line == "general game updates:") {
                current_section = "game";
            }
            else if (line == "team a updates:") {
                current_section = "team_a";
            }
            else if (line == "team b updates:") {
                current_section = "team_b";
            }
            else if (line == "description:") {
                current_section = "desc";
            }
            // Handle Content based on section
            else {
                if (current_section == "desc") {
                    description += line + "\n";
                }
                else if (current_section == "game" || current_section == "team_a" || current_section == "team_b") {
                    // Expecting indented "  key: value"
                    size_t colon = line.find(':');
                    if (colon != string::npos) {
                        string key = trim(line.substr(0, colon));
                        string val = trim(line.substr(colon + 1));
                        
                        if (current_section == "game") game_updates[key] = val;
                        else if (current_section == "team_a") team_a_updates[key] = val;
                        else if (current_section == "team_b") team_b_updates[key] = val;
                    }
                }
            }
        }


        string gameName;
        // C. Construct Event using the DETAILED constructor
        if (subId != -1 && subIdToGame.count(subId) && !senderUserName.empty()) {
            gameName = subIdToGame[subId];
            
            Event newEvent(team_a_name, team_b_name, event_name, time_val, 
                           game_updates, team_a_updates, team_b_updates, description);
            
            game_to_user_events[gameName][senderUserName].push_back(newEvent);
        }

        return "Event received for game: " + gameName + " user: " + senderUserName;
    }

    return "";
}

// --- Frame Creation Helpers ---

string StompProtocol::createConnectFrame(string host, string user, string pass) { 
    {
        lock_guard<mutex> lock(loginMutex);
        isLoggedIn = false;
        loginError = false;
        logoutCompleted = false;
    }
    return "CONNECT\naccept-version:1.2\nhost:" + host + "\nlogin:" + user + "\npasscode:" + pass + "\n\n";
}

string StompProtocol::createSubscribeFrame(string gameName) {
    if (gameToSubId.find(gameName) != gameToSubId.end()) {
        cout << "You are already subscribed to " << gameName << endl;
        return "";
    }

    int subId = subCounter++;
    int receiptId = receiptCounter++;
    gameToSubId[gameName] = subId;
    subIdToGame[subId] = gameName; 
    
    receiptToActions[receiptId] = "Joined channel " + gameName;
    
    return "SUBSCRIBE\ndestination:/" + gameName + "\nid:" + to_string(subId) + "\nreceipt:" + to_string(receiptId) + "\n\n";
}

string StompProtocol::createUnsubscribeFrame(string gameName) {
    if (gameToSubId.find(gameName) == gameToSubId.end()){
        cout << "You are not subscribed to " << gameName << endl;
        return ""; 
    }
    int subId = gameToSubId[gameName];
    int receiptId = receiptCounter++;
    receiptToActions[receiptId] = "Exited channel " + gameName;
    gameToSubId.erase(gameName);
    return "UNSUBSCRIBE\nid:" + to_string(subId) + "\nreceipt:" + to_string(receiptId) + "\n\n";
}

string StompProtocol::createDisconnectFrame() {
    int receiptId = receiptCounter++;
    receiptToActions[receiptId] = "logout";
    return "DISCONNECT\nreceipt:" + to_string(receiptId) + "\n\n";
}

string StompProtocol::createSendFrame(const Event& event, const string& userName,const string& filename) {
    stringstream frame;
    frame << "SEND" << "\n";
    frame << "destination:/" << event.get_team_a_name() << "_" << event.get_team_b_name() << "\n";
    frame << "filename:" << filename << "\n";

    frame << "\n"; 

    frame << "user: " << userName << "\n";
    frame << "team a: " << event.get_team_a_name() << "\n";
    frame << "team b: " << event.get_team_b_name() << "\n";
    frame << "event name: " << event.get_name() << "\n";
    frame << "time: " << event.get_time() << "\n";

    frame << "general game updates:\n";
    for (auto const& it : event.get_game_updates()) {
        frame << "    " << it.first << ": " << it.second << "\n";
    }

    frame << "team a updates:\n";
    for (auto const& it : event.get_team_a_updates()) {
        frame << "    " << it.first << ": " << it.second << "\n";
    }

    frame << "team b updates:\n";
    for (auto const& it : event.get_team_b_updates()) {
        frame << "    " << it.first << ": " << it.second << "\n";
    }  

    frame << "description:\n" << event.get_discription() << "\n";
    frame << '\0'; 

    return frame.str();
}

void StompProtocol::generateSummary(std::string gameName, std::string userName, std::string fileName) {
    // 1. Check if data exists
    if (game_to_user_events.find(gameName) == game_to_user_events.end() ||
        game_to_user_events[gameName].find(userName) == game_to_user_events[gameName].end()) {
        std::cout << "No events found for game " << gameName << " from user " << userName << std::endl;
        return;
    }

    std::vector<Event>& events = game_to_user_events[gameName][userName];
    if (events.empty()) return;

    // Sort by time
    std::sort(events.begin(), events.end(), [](const Event& a, const Event& b) {
        return a.get_time() < b.get_time();
    });

    std::map<std::string, std::string> game_stats;
    std::map<std::string, std::string> team_a_stats;
    std::map<std::string, std::string> team_b_stats;

    std::string team_a_name = events[0].get_team_a_name();
    std::string team_b_name = events[0].get_team_b_name();

    for (const auto& event : events) {
        for (const auto& pair : event.get_game_updates()) {
            game_stats[pair.first] = pair.second;
        }
        for (const auto& pair : event.get_team_a_updates()) {
            team_a_stats[pair.first] = pair.second;
        }
        for (const auto& pair : event.get_team_b_updates()) {
            team_b_stats[pair.first] = pair.second;
        }
    }

    std::ofstream outFile("data/" + fileName);
    if (!outFile.is_open()) {
        std::cout << "Error opening file: " << fileName << std::endl;
        return;
    }

    outFile << team_a_name << " vs " << team_b_name << "\n";
    outFile << "Game Stats:\n";
    
    outFile << "General stats:\n";
    for (const auto& pair : game_stats) outFile << pair.first << ": " << pair.second << "\n";

    outFile << team_a_name << " stats:\n";
    for (const auto& pair : team_a_stats) outFile << pair.first << ": " << pair.second << "\n";

    outFile << team_b_name << " stats:\n";
    for (const auto& pair : team_b_stats) outFile << pair.first << ": " << pair.second << "\n";

    outFile << "Game Event Reports:\n";
    for (const auto& event : events) {
        outFile << event.get_time() << " - " << event.get_name() << ":\n\n";
        outFile << event.get_discription() << "\n\n\n";
    }

    outFile.close();
    std::cout << "Summary created successfully in " << fileName << std::endl;
}