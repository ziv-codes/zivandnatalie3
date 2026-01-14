#include <iostream>
#include <thread>
#include <sstream>
#include "../include/ConnectionHandler.h"
#include "../include/StompProtocol.h"

using namespace std;

void socketReaderTask(ConnectionHandler& handler, StompProtocol& protocol) {
    while (true) {
        string frame;
        if (!handler.getFrameAscii(frame, '\0')) {   //מבטיח שגם בעת אררור התרד יסגר
            break;
        }
        string response = protocol.processServerFrame(frame);
        if (!response.empty()) cout << response << endl;
		
		stringstream response_ss(response);
		string firstWord;
		response_ss >> firstWord; // מחלץ את המילה הראשונה (עד הרווח הראשון)

		// בדיקה האם המילה הראשונה היא logout או Error
		if (firstWord == "logout" || firstWord == "Error") {
			break;
		}
	}
}

int main(int argc, char *argv[]) {
    ConnectionHandler* handler = nullptr;
    StompProtocol protocol;
    string currentUserName = "";

    while (true) {
        const short bufSize = 1024;
        char buf[bufSize];
        if (!cin.getline(buf, bufSize)) break;
        string userInput(buf);
        stringstream userInputSS(userInput);
        string command;
        userInputSS >> command;

        bool isLoggedIn = protocol.getIsLoggedIn(); 

        if (command == "login") {
            if (isLoggedIn) {
                cout << "The client is already logged in." << endl;
                continue;
            }

            string hostPort, user, pass;
            userInputSS >> hostPort >> user >> pass;
            size_t colonPos = hostPort.find(':');
            string host = hostPort.substr(0, colonPos);
            short port = static_cast<short>(stoi(hostPort.substr(colonPos + 1)));

            handler = new ConnectionHandler(host, port);
            if (!handler->connect()) {
                cout << "Could not connect to server" << endl;
                delete handler; handler = nullptr;
                continue;
            }

            // הפעלת ת'רד קריאה
            thread readerThread(socketReaderTask, ref(*handler), ref(protocol));
            readerThread.detach(); // התרד יסגר אוטמטית במקרה של התנתנקות / שגיאה

            // שליחת CONNECT והמתנה לאישור
            handler->sendFrameAscii(protocol.createConnectFrame("stomp.cs.bgu.ac.il", user, pass), '\0');
            
            if (protocol.waitForLogin()) {
                currentUserName = user; //החיבור הצליח
            } else { // שגיאה ב
                delete handler; 
                handler = nullptr;
            }
        }
        else if (command == "logout") {
            if (!isLoggedIn) {
                cout << "You are not logged in" << endl;
                continue;
            }
            handler->sendFrameAscii(protocol.createDisconnectFrame(), '\0');
            //ממתינים לתשובת שרת לפני סגירת החיבור
            if (protocol.waitForLogout()) {
                cout << "Logged out successfully" << endl;
            }

            delete handler; handler = nullptr;
            currentUserName = ""; // איפוס שם המשתמש לאחר התנתקות
        }
        else if (isLoggedIn) {
            string frames = protocol.parseUserCommand(userInput, currentUserName);
			if (frames=="")
				continue;
            stringstream frameStream(frames);
            string singleFrame;
            while (getline(frameStream, singleFrame, '\0')) { //אם יש כמה פריימים - במקרה של report
                if (!singleFrame.empty()) handler->sendFrameAscii(singleFrame, '\0');
            }
        }
        else cout << "Please login first" << endl;
    }

    if (handler) delete handler;
    return 0;
}