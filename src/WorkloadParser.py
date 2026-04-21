"""Workload parser and helper HTTP client for the assignment.

This module implements simple command objects for PRODUCT/USER/ORDER
workload lines and helper functions to contact the microservices.

Usage: python3 WorkloadParser.py <workload-file>
"""

import sys
import json
from typing import Any

import requests

VALID_STATUS_CODE = 200
ERROR_STATUS_CODE = 400

class SystemCommand:
    """Handles system-level commands like shutdown and restart."""
    def __init__(self, command): 
        self.command = command 
        
    def execute(self, id, port): 
        # POST directly to /shutdown or /restart on OrderServer
        response = post_json(id, port, f"/{self.command}", {}) 
        print(response)


class Order:
    """Base class for order-related workload commands.

    Subclasses should implement :py:meth:`execute` to perform their action
    against the services.
    """
    def __init__(self):
        self.product_id = None
        self.user_id = None
        self.quantity = None

    def execute(self, id, port):
        pass

class OrderPlace(Order):
    """Place-order command parsed from a workload line.

    Expected token format: ORDER place <product_id> <user_id> <quantity>
    """
    def __init__(self, tokens):
        super().__init__()
        if len(tokens) != 5:
            raise ValueError

        self.product_id = int(tokens[2])
        self.user_id = int(tokens[3])
        self.quantity = int(tokens[4])

    def execute(self, id, port):

        payload = {
            "command": "place order",
            "product_id": self.product_id,
            "user_id": self.user_id,
            "quantity": self.quantity
        }

        response = post_json(id,
                  port,
                  "/order",
                  payload)
        print(response)


class User:
    """Base class for user-related workload commands."""
    def __init__(self):
        self.id = None
        self.username = None
        self.email = None
        self.password = None

    def execute(self, id, port):
        pass

class UserGet(User):
    """Handles a USER get <id> command."""
    def __init__(self, tokens):
        super().__init__()

        if len(tokens) != 3:
            raise ValueError

        self.id = int(tokens[2])

    def execute(self, id, port):

        payload = {
            "id": self.id,
        }

        response = get_json(id,
                            port,
                            "/user/" + str(self.id),
                            payload)
        print(response)
        if response["status_code"] == VALID_STATUS_CODE:
            text = response["response_text"]
            return text
        elif response["status_code"] == ERROR_STATUS_CODE:
            return ERROR_STATUS_CODE

class UserCreate(User):
    """Handles a USER create <id> <username> <email> <password> command."""
    def __init__(self, tokens):
        super().__init__()

        if len(tokens) != 6:
            raise ValueError("Invalid Input")

        self.id = int(tokens[2])

        self.username = tokens[3]
        self.email = tokens[4]
        self.password = tokens[5]

    def execute(self, id, port):

        payload = {
            "command": "create",
            "id": self.id,
            "username": self.username,
            "email": self.email,
            "password": self.password
        }

        response = post_json(id,
                             port,
                             "/user",
                             payload)
        print(response)

class UserUpdate(User):
    """Handles a USER update <id> [username:..] [email:..] [password:..] command."""
    def __init__(self, tokens):
        super().__init__()

        if len(tokens) < 3:
            raise ValueError("PRODUCT update requires an id")

        self.id = int(tokens[2])

        for token in tokens[3:]:
            if token.startswith("username:"):
                self.username = token.split(":")[1]
            elif token.startswith("email:"):
                self.email = token.split(":")[1]
            elif token.startswith("password:"):
                self.password = token.split(":")[1]
            else:
                raise ValueError("Invalid update field")

    def execute(self, id, port):

        payload = {
            "command": "update",
            "id": self.id,
            "username": self.username,
            "email": self.email,
            "password": self.password,
        }

        response = post_json(id,
                             port,
                             "/user",
                             payload)
        print(response)

class UserDelete(User):
    """Handles a USER delete <id> <username> <email> <password> command."""
    def __init__(self, tokens):
        super().__init__()
        if len(tokens) != 6:
            raise ValueError("Invalid Input")
        self.id = int(tokens[2])
        self.username = tokens[3]
        self.email = tokens[4]
        self.password = tokens[5]
    def execute(self, id, port):

        payload = {
            "command": "delete",
            "id": self.id,
            "username": self.username,
            "email": self.email,
            "password": self.password,
        }
        response = post_json(id,
                             port,
                             "/user",
                             payload)
        print(response)


class Product:
    """Base class for product-related workload commands."""
    def __init__(self):
        self.id = None
        self.quantity = None
        self.product_name = None
        self.price = None
        self.description = None

    def execute(self, id, port):
        pass

class ProductInfo(Product):
    """Handles PRODUCT info <id> command."""
    def __init__(self, tokens):
        super().__init__()

        if len(tokens) != 3:
            raise ValueError

        self.id = int(tokens[2])

    def execute(self, id, port):
        payload = {
            "id": self.id
        }
        response = get_json(id,
                            port,
                            "/product/" + str(self.id),
                            payload)
        print(response)
        if response["status_code"] == VALID_STATUS_CODE:
            text = response["response_text"]
            return text
        elif response["status_code"] == ERROR_STATUS_CODE:
            return ERROR_STATUS_CODE

class ProductCreate(Product):
    """Handles PRODUCT create <id> <name> <description> <price> <quantity> command."""
    def __init__(self, tokens):
        super().__init__()

        if len(tokens) != 7:
            raise ValueError("Invalid Input")

        self.id = int(tokens[2])

        self.product_name = tokens[3]
        self.description = tokens[4]
        self.price = float(tokens[5])
        self.quantity = int(tokens[6])

    def execute(self, id, port):

        payload = {
            "command": "create",
            "id": self.id,
            "name": self.product_name,
            "description": self.description,
            "price": self.price,
            "quantity": self.quantity
        }
        response = post_json(id,
                             port,
                             "/product",
                             payload)
        print(response)

class ProductUpdate(Product):
    """Handles PRODUCT update <id> with optional field updates.

    Supports tokens like "price:12.3" or "name:widget" after the id.
    """
    def __init__(self, tokens):
        super().__init__()

        if len(tokens) < 3:
            raise ValueError("PRODUCT update requires an id")

        self.id = int(tokens[2])

        for token in tokens[3:]:
            if token.startswith("name:"):
                self.product_name = token.split(":")[1]
            elif token.startswith("price:"):
                self.price = float(token.split(":")[1])
            elif token.startswith("description:"):
                self.description = token.split(":")[1]
            elif token.startswith("quantity:"):
                self.quantity = int(token.split(":")[1])
            else:
                raise ValueError("Invalid update field")

    def execute(self, id, port):

        payload = {
            "command": "update",
            "id": self.id,
            "name": self.product_name,
            "description": self.description,
            "price": self.price,
            "quantity": self.quantity
        }
        response = post_json(id,
                             port,
                             "/product",
                             payload)
        print(response)

class ProductDelete(Product):
    """Handles PRODUCT delete <id> <name> <price> <quantity> command."""
    def __init__(self, tokens):
        super().__init__()

        if len(tokens) != 6:
            raise ValueError("Invalid Input")

        self.id = int(tokens[2])
        self.product_name = tokens[3]
        self.price = float(tokens[4])
        self.quantity = int(tokens[5])

    def execute(self, id, port):

        payload = {
            "command": "delete",
            "id": self.id,
            "name": self.product_name,
            "price": self.price,
            "quantity": self.quantity
        }

        response = post_json(id,
                             port,
                             "/product",
                             payload)
        print(response)


def parser(filename):
    """Parse a workload file and execute commands against services.

    The function reads `config.json` in the current directory to find service
    addresses, then reads the given workload file line by line and dispatches
    commands.

    :param filename: path to workload file
    :return: None
    """
    first_line = 0
    with open("config.json") as f:
        config = json.load(f)

    user_ip = config["UserService"]["ip"]
    user_port = config["UserService"]["port"]

    product_ip = config["ProductService"]["ip"]
    product_port = config["ProductService"]["port"]

    order_ip = config["OrderService"]["ip"]
    order_port = config["OrderService"]["port"]

    with open(filename) as f:
        for line in f:
            first_line += 1
            try:
                tokens = line.strip().split()
                if not tokens:
                    continue
                
                if tokens[0] == "shutdown": 
                    command = SystemCommand("shutdown") 
                    command.execute(order_ip, order_port) 
                    continue 

                elif tokens[0] == "restart": 
                    if first_line == 1:
                        command = SystemCommand("restart") 
                        command.execute(order_ip, order_port) 
                        continue
                    

                if tokens[0] == "PRODUCT":
                    action = tokens[1]

                    if action == "create":
                        command = ProductCreate(tokens)
                    elif action == "update":
                        command = ProductUpdate(tokens)
                    elif action == "DELETE":
                        command = ProductDelete(tokens)
                    elif action == "info":
                        command = ProductInfo(tokens)
                    else:
                        raise ValueError("Unknown PRODUCT action")
                    command.execute(order_ip, order_port)

                elif tokens[0] == "USER":
                    action = tokens[1]

                    if action == "create":
                        command = UserCreate(tokens)
                    elif action == "update":
                        command = UserUpdate(tokens)
                    elif action == "delete":
                        command = UserDelete(tokens)
                    elif action == "get":
                        command = UserGet(tokens)
                    else:
                        raise ValueError("Unknown PRODUCT action")
                    command.execute(order_ip, order_port)

                elif tokens[0] == "ORDER":
                    action = tokens[1]

                    if action == "place":
                        command = OrderPlace(tokens)
                    else:
                        raise ValueError("Unknown ORDER action")
                    command.execute(order_ip, order_port)

            except Exception:
                print("Invalid input\n")
        return None

def get_json(ip, port, endpoint, payload, timeout=10):
    """Perform a GET request to a service and return status and text.

    :param ip: service IP
    :param port: service port
    :param endpoint: request path (e.g. '/user/1')
    :param payload: JSON-serializable payload sent as request body
    :param timeout: request timeout in seconds
    :return: dict with keys `status_code` and `response_text`
    """
    url = f"http://{ip}:{port}{endpoint}"
    r = requests.request(
        "GET",
        url,
        json=payload,  # BODY
        timeout=timeout,
        headers={"Content-Type": "application/json"},
    )
    return {"status_code": r.status_code, "response_text": r.text}

def post_json(ip: str, port: int, endpoint: str, payload: dict, timeout=10):
    """Perform a POST request to a service and return the response status.

    :param ip: service IP
    :param port: service port
    :param endpoint: request path
    :param payload: JSON-serializable payload
    :param timeout: request timeout in seconds
    :return: HTTP status code on success or None on network error
    """
    url = f"http://{ip}:{port}{endpoint}"
    try:
        response = requests.post(url, json=payload, timeout=timeout)
        return response.status_code
    except requests.exceptions.RequestException as e:
        return None

if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: python3 WorkloadParser.py <filename>")
        sys.exit(1)

    filename = sys.argv[1]
    parser(filename)