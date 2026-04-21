import json
import http.client
import os

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(BASE_DIR, "..", "config.json")
TESTCASES_PATH = os.path.join(BASE_DIR, "payloads", "product_testcases.json")

with open(CONFIG_PATH, "r") as f:
    config = json.load(f)

HOST = config["OrderService"]["ip"]
PORT = config["OrderService"]["port"]

def send_request(test_name, data):
    conn = http.client.HTTPConnection(HOST, PORT)
    headers = {"Content-Type": "application/json"}

    # PRODUCT GET (info)
    if "_get_" in test_name:
        product_id = data.get("id")
        path = f"/product/{product_id}"
        conn.request("GET", path)

    # PRODUCT POST (create / update / delete)
    else:
        path = "/product"
        conn.request(
            "POST",
            path,
            body=json.dumps(data),
            headers=headers
        )

    response = conn.getresponse()
    print(f"Path: {path}")
    print(f"Status: {response.status}")
    print("Response:", response.read().decode())
    conn.close()

def main():
    with open(TESTCASES_PATH, "r") as f:
        testcases = json.load(f)

    for name, testcase in testcases.items():
        print(f"Running test case: {name}")
        send_request(name, testcase)
        print("-" * 40)

if __name__ == "__main__":
    main()
