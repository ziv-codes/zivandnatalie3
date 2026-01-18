#include "../include/StompProtocol.h"
#include "../include/event.h"
#include <sstream>
#include <iostream>
#include <fstream>
#include <algorithm>

using namespace std;

StompProtocol::StompProtocol() : isLoggedIn(false), loginError(false), logoutCompleted(false), subCounter(0), receiptCounter(0) {}

bool StompProtocol::getIsLoggedIn() const { return isLoggedIn; }

bool StompProtocol::waitForLogin() {

    std::unique_lock<std::mutex> lock(loginMutex);
    loginCV.wait(lock, [this] {
        return isLoggedIn || loginError; 
    }); 
    
    // ברגע שהתעוררנו, אנחנו מחזירים האם החיבור הצליח או נכשל
    return isLoggedIn;
}

bool StompProtocol::waitForLogout() {
    std::unique_lock<std::mutex> lock(loginMutex);
    
    // המתנה ללא הגבלת זמן עד שדגל הניתוק יתעדכן ל-true
    loginCV.wait(lock, [this] {
        return logoutCompleted;
    });

    return true; 
}

string StompProtocol::parseUserCommand(string input, string& userName) {
    stringstream userInputSS(input);
    string command;
    userInputSS >> command;

    if (command == "join") {
        string gameName; userInputSS >> gameName;
        return createSubscribeFrame(gameName);
    } 
    else if (command == "exit") {
        string gameName; userInputSS >> gameName;
        return createUnsubscribeFrame(gameName);
    }
    else if (command == "report") {
            string fileName; 
            userInputSS >> fileName;
            
            names_and_events report_data = parseEventsFile(fileName);
            string allFrames = "";
            
            for (const auto& event : report_data.events) {
                // קריאה לפעולה הנפרדת עבור כל אירוע
                allFrames += createSendFrame(event, userName, fileName);
            }
            return allFrames;
    }

    
    else if (command == "logout") return createDisconnectFrame();
    
    return "";
}

string StompProtocol::processServerFrame(string frame) {
    stringstream frameSS(frame);
    string command;
    getline(frameSS, command);

    if (command == "CONNECTED") {
        {
            lock_guard<mutex> lock(loginMutex);
            isLoggedIn = true;
        }
        loginCV.notify_all();
        return "Login successful";
    }
    else if (command == "RECEIPT") {
        string receiptHeader; getline(frameSS, receiptHeader);
        int rId = stoi(receiptHeader.substr(receiptHeader.find(':') + 1));
        string action = receiptToActions[rId];
        receiptToActions.erase(rId);
        if (action == "logout") {
            {
                lock_guard<mutex> lock(loginMutex);
                isLoggedIn = false;     // אנחנו כבר לא מחוברים
                logoutCompleted = true; // הניתוק הסתיים רשמית
            }
            loginCV.notify_all(); // מעירים את ה-Main שמחכה ב-logout
            return "logout"; 
        }


        return action;
    }
    else if (command == "ERROR") {
        {
            lock_guard<mutex> lock(loginMutex);
            loginError = true;
            isLoggedIn = false;
        }
        loginCV.notify_all();
        return "Error from server: " + frame;
    }
    else if (command == "MESSAGE") {
        string line;
        int subId = -1;
        std::map<string, string> headers; // מפה זמנית לכל הכותרות בפריים

        // 1. קריאת כל ה-Headers ושמירתם במפה
        while (getline(frameSS, line) && line != "") {
            size_t colonPos = line.find(':');
            if (colonPos != string::npos) {
                string key = line.substr(0, colonPos);
                string value = line.substr(colonPos + 1);
                headers[key] = value;
            }
        }

        // 2. שליפת ה-subscription ID מתוך המפה
        if (headers.count("subscription")) {
            subId = stoi(headers["subscription"]);
        }


        // אפשר לשלוף כאן headers נוספים אם נצטרך בעתיד
        // למשל: string dest = headers["destination"];

        // 3. קריאת ה-Body (נשאר אותו דבר)
        string body = "";
        string bodyLine;
        string senderUserName = "";

        bool usernameFound = false;
        while (getline(frameSS, bodyLine)) {
            body += bodyLine + "\n";
            if (!usernameFound && bodyLine.find("user: ") == 0) {
                usernameFound=true;
                senderUserName = bodyLine.substr(6);
            }
        }


        // 4. שמירה
        if (subIdToGame.count(subId) && !senderUserName.empty()) {
            string gameName = subIdToGame[subId];
            Event newEvent(body);
            game_to_user_events[gameName][senderUserName].push_back(newEvent);
        }

        return "MESSAGE"; 
    }


    return "Message received:\n" + frame;
}

string StompProtocol::createConnectFrame(string host, string user, string pass) { // פונקציות העזר לייצור פריימים

    // איפוס דגלים כדי לאפשר לוגין וניתוק חדשים בסשן הנוכחי
    {
        lock_guard<mutex> lock(loginMutex);
        isLoggedIn = false;
        loginError = false;
        logoutCompleted = false;
    }
    return "CONNECT\naccept-version:1.2\nhost:" + host + "\nlogin:" + user + "\npasscode:" + pass + "\n\n";
}

string StompProtocol::createSubscribeFrame(string gameName) {
    // 1. בדיקה האם המשחק כבר קיים במפת המנויים
    if (gameToSubId.find(gameName) != gameToSubId.end()) {
        cout << "You are already subscribed to " << gameName << endl;
        return "";
    }

    // 2. אם לא רשומים, ממשיכים בתהליך כרגיל
    int subId = subCounter++;
    int receiptId = receiptCounter++;
    gameToSubId[gameName] = subId;
    subIdToGame[subId] = gameName; 
    
    receiptToActions[receiptId] = "Joined channel " + gameName;
    
    return "SUBSCRIBE\ndestination:/" + gameName + "\nid:" + to_string(subId) + "\nreceipt:" + to_string(receiptId) + "\n\n";
}

string StompProtocol::createUnsubscribeFrame(string gameName) {
    if (gameToSubId.find(gameName) == gameToSubId.end()) return ""; // אם המנוי לא רשום בכלל לצ'אנל
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

    // 1. Command ו-Headers
    frame << "SEND" << "\n";
    // ה-destination נבנה משמות שתי הקבוצות
    frame << "destination:/" << event.get_team_a_name() << "_" << event.get_team_b_name() << "\n";
    frame << "filename:" << filename << "\n";

    frame << "\n"; // שורה ריקה חובה בין ה-Headers ל-Body

    // 2. Body (לפי הפורמט הנדרש ב-SPL)
    frame << "user: " << userName << "\n";
    frame << "team a: " << event.get_team_a_name() << "\n";
    frame << "team b: " << event.get_team_b_name() << "\n";
    frame << "event name: " << event.get_name() << "\n";
    frame << "time: " << event.get_time() << "\n";

    frame << "general game updates:\n";
    // שינוי כאן: שימוש ב-pair במקום structured binding
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


    // שימוש ב-get_discription כפי שמופיע ב-event.cpp שלך
    frame << "description:\n" << event.get_discription() << "\n";

    frame << '\0'; // סיום הפריים בתו Null

    return frame.str();
}


// יש להוסיף למעלה: #include <fstream>

void StompProtocol::generateSummary(std::string gameName, std::string userName, std::string fileName) {
    // 1. בדיקה אם המידע קיים במפה המקוננת
    if (game_to_user_events.find(gameName) == game_to_user_events.end() ||
        game_to_user_events[gameName].find(userName) == game_to_user_events[gameName].end()) {
        std::cout << "No events found for game " << gameName << " from user " << userName << std::endl;
        return;
    }

    // שליפת הוקטור של האירועים
    std::vector<Event>& events = game_to_user_events[gameName][userName];
    if (events.empty()) return;


    //מיון לפי הזמנים
    std::sort(events.begin(), events.end(), [](const Event& a, const Event& b) {
        return a.get_time() < b.get_time();
    });


    // 2. חישוב סטטיסטיקות מסכמות (לוקחים את הערך האחרון שדווח לכל מפתח)
    std::map<std::string, std::string> game_stats;
    std::map<std::string, std::string> team_a_stats;
    std::map<std::string, std::string> team_b_stats;

    // שמות הקבוצות נלקחים מהאירוע הראשון (הם קבועים לאורך המשחק)
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

    // 3. כתיבה לקובץ
    std::ofstream outFile(fileName);
    if (!outFile.is_open()) {
        std::cout << "Error opening file: " << fileName << std::endl;
        return;
    }

    // כותרת ראשית
    outFile << team_a_name << " vs " << team_b_name << "\n";
    outFile << "Game Stats:\n";
    
    outFile << "General stats:\n";
    for (const auto& pair : game_stats) outFile << pair.first << ": " << pair.second << "\n";

    outFile << team_a_name << " stats:\n";
    for (const auto& pair : team_a_stats) outFile << pair.first << ": " << pair.second << "\n";

    outFile << team_b_name << " stats:\n";
    for (const auto& pair : team_b_stats) outFile << pair.first << ": " << pair.second << "\n";

    // פירוט האירועים
    outFile << "Game Event Reports:\n";
    for (const auto& event : events) {
        outFile << event.get_time() << " - " << event.get_name() << ":\n\n";
        outFile << event.get_discription() << "\n\n\n";
    }

    outFile.close();
    std::cout << "Summary created successfully in " << fileName << std::endl;
}