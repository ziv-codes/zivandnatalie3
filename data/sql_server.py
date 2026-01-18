#!/usr/bin/env python3
"""
Basic Python Server for STOMP Assignment – Stage 3.3
"""

import socket
import sys
import threading
import sqlite3
import os

SERVER_NAME = "STOMP_PYTHON_SQL_SERVER"  # DO NOT CHANGE!
DB_FILE = "stomp_server.db"              # DO NOT CHANGE!


def recv_null_terminated(sock: socket.socket) -> str:
    data = b""
    while True:
        chunk = sock.recv(1024)
        if not chunk:
            return ""
        data += chunk
        if b"\0" in data:
            msg, _ = data.split(b"\0", 1)
            return msg.decode("utf-8", errors="replace")


def init_database():
    """ Initialize the database with required tables. """
    try:
        conn = sqlite3.connect(DB_FILE)
        cursor = conn.cursor()
        
        # 1. Users Table
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS Users (
                username TEXT PRIMARY KEY,
                password TEXT NOT NULL
            )
        ''')

        # 2. Login History
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS Login_History (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT NOT NULL,
                login_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                logout_time DATETIME,
                FOREIGN KEY(username) REFERENCES Users(username)
            )
        ''')

        # 3. Uploaded Files
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS Uploaded_Files (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT NOT NULL,
                filename TEXT NOT NULL,
                upload_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY(username) REFERENCES Users(username)
            )
        ''')
        
        conn.commit()
        conn.close()
        print(f"[{SERVER_NAME}] Database initialized successfully.")
    except Exception as e:
        print(f"[{SERVER_NAME}] Error initializing DB: {e}")


def execute_sql_command(sql_command: str) -> str:
    """ Executes INSERT/UPDATE/DELETE and returns 'done' or error message """
    conn = None
    try:
        conn = sqlite3.connect(DB_FILE)
        cursor = conn.cursor()
        cursor.execute(sql_command)
        conn.commit()
        return "done"
    except Exception as e:
        return f"Error: {e}"
    finally:
        if conn:
            conn.close()


def execute_sql_query(sql_query: str) -> str:
    """ Executes SELECT and returns the result string """
    conn = None
    try:
        conn = sqlite3.connect(DB_FILE)
        cursor = conn.cursor()
        cursor.execute(sql_query)
        rows = cursor.fetchall()
        
        result_str = ""
        for row in rows:
            line = " ".join(str(item) for item in row)
            result_str += line + "\n"
            
        return result_str.strip()
    except Exception as e:
        return f"Error: {e}"
    finally:
        if conn:
            conn.close()


def handle_client(client_socket: socket.socket, addr):
    print(f"[{SERVER_NAME}] Client connected from {addr}")

    try:
        while True:
            message = recv_null_terminated(client_socket)
            if message == "":
                break

            print(f"[{SERVER_NAME}] Received:")
            print(message)

            # --- Logic to support SQL ---
            response = ""
            msg_upper = message.strip().upper()
            
            if msg_upper.startswith("SELECT"):
                response = execute_sql_query(message)
            else:
                response = execute_sql_command(message)

            # Send response + null terminator
            client_socket.sendall(response.encode("utf-8") + b"\0")

    except Exception as e:
        print(f"[{SERVER_NAME}] Error handling client {addr}: {e}")
    finally:
        try:
            client_socket.close()
        except Exception:
            pass
        print(f"[{SERVER_NAME}] Client {addr} disconnected")


def start_server(host="127.0.0.1", port=7778):
    
    init_database()
    
    server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

    try:
        server_socket.bind((host, port))
        server_socket.listen(5)
        print(f"[{SERVER_NAME}] Server started on {host}:{port}")
        print(f"[{SERVER_NAME}] Waiting for connections...")

        while True:
            client_socket, addr = server_socket.accept()
            t = threading.Thread(
                target=handle_client,
                args=(client_socket, addr),
                daemon=True
            )
            t.start()

    except KeyboardInterrupt:
        print(f"\n[{SERVER_NAME}] Shutting down server...")
    finally:
        try:
            server_socket.close()
        except Exception:
            pass


if __name__ == "__main__":
    port = 7778
    if len(sys.argv) > 1:
        raw_port = sys.argv[1].strip()
        try:
            port = int(raw_port)
        except ValueError:
            print(f"Invalid port '{raw_port}', falling back to default {port}")

    start_server(port=port)