"""Generador de datos sintéticos para el pipeline medallion.
Crea tres CSV en landing/: customers.csv, products.csv, orders.csv.
Usa solo la librería estándar: csv, random, datetime.
Semilla fija (42) para reproducibilidad.
Suciedad realista en orders.csv: 3% con quantity vacío o negativo,
2% con customer_id inexistente, ~50 filas duplicadas enteras.
"""
import csv
import random
import datetime
import os

random.seed(42)

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
LANDING_DIR = os.path.join(BASE_DIR, "..", "landing")
os.makedirs(LANDING_DIR, exist_ok=True)

# -----------------------------------------------------------
# Parámetros
# -----------------------------------------------------------
COUNTRIES = ["ES", "FR", "DE", "PT"]
CATEGORIES = ["books", "toys", "food"]
PRODUCT_COUNT = 40
CUSTOMER_COUNT = 200
ORDER_COUNT = 5000
DUPLICATE_COUNT = 50

# -----------------------------------------------------------
# Generar clientes
# -----------------------------------------------------------
customers = []
for i in range(1, CUSTOMER_COUNT + 1):
    country = random.choice(COUNTRIES)
    start = datetime.date(2023, 1, 1)
    end = datetime.date(2024, 12, 31)
    days = (end - start).days
    signup = start + datetime.timedelta(days=random.randint(0, days))
    customers.append({
        "customer_id": i,
        "name": f"Customer_{i}",
        "country": country,
        "signup_date": signup.isoformat(),
    })

valid_customer_ids = set(c["customer_id"] for c in customers)

# -----------------------------------------------------------
# Generar productos
# -----------------------------------------------------------
products = []
for i in range(1, PRODUCT_COUNT + 1):
    cat = random.choice(CATEGORIES)
    price = round(random.uniform(5, 150), 2)
    products.append({
        "product_id": i,
        "name": f"Product_{i}",
        "category": cat,
        "unit_price": price,
    })

# -----------------------------------------------------------
# Generar orders base (solo 5000 filas, sin duplicados aún)
# -----------------------------------------------------------
orders = []
for i in range(ORDER_COUNT):
    order = {
        "order_id": i + 1,                      # order_ids fijos 1-5000
        "customer_id": random.choice(list(valid_customer_ids)),
        "product_id": random.randint(1, PRODUCT_COUNT),
        "quantity": random.randint(1, 10),
        "order_ts": datetime.datetime(2024, 1, 1) + datetime.timedelta(
            seconds=random.randint(0, 365 * 24 * 3600)
        ),
        "status": random.choices(["COMPLETED", "CANCELLED"], weights=[90, 10])[0],
    }
    orders.append(order)

# -----------------------------------------------------------
# Insertar customer_id inexistente en las primeras 2% filas
# -----------------------------------------------------------
num_bad_customer = ORDER_COUNT * 2 // 100
# Usar IDs fuera del rango 1-200
outside_ids = list(range(201, 201 + num_bad_customer))
for j in range(num_bad_customer):
    orders[j]["customer_id"] = outside_ids[j]

# -----------------------------------------------------------
# Insertar quantity vacío o negativo en las primeras 3% filas
# -----------------------------------------------------------
num_bad_qty = ORDER_COUNT * 3 // 100
for j in range(num_bad_qty):
    if random.random() < 0.5:
        orders[j]["quantity"] = -random.randint(1, 5)  # negativo
    else:
        orders[j]["quantity"] = ""  # vacío

# -----------------------------------------------------------
# Insertar ~50 filas duplicadas enteras
# Elegimos índices únicos y las insertamos después de la fila original.
# Como insertamos, el total crece a 5050. Luego re-asignaremos order_ids.
# Insertamos en orden inverso para que los índices no se desplacen.
# -----------------------------------------------------------
dup_indices = sorted(random.sample(range(ORDER_COUNT), DUPLICATE_COUNT), reverse=True)
for idx in dup_indices:
    dup_order = dict(orders[idx])
    orders.insert(idx + 1, dup_order)

# -----------------------------------------------------------
# order_ids: se mantienen los originales para que los duplicados
# tengan el mismo order_id que la fila de origen (no se re-asigna)

# -----------------------------------------------------------
# Escribir CSV customers
# -----------------------------------------------------------
with open(os.path.join(LANDING_DIR, "customers.csv"), "w", newline="", encoding="utf-8") as f:
    writer = csv.DictWriter(f, fieldnames=["customer_id", "name", "country", "signup_date"])
    writer.writeheader()
    writer.writerows(customers)

# -----------------------------------------------------------
# Escribir CSV products
# -----------------------------------------------------------
with open(os.path.join(LANDING_DIR, "products.csv"), "w", newline="", encoding="utf-8") as f:
    writer = csv.DictWriter(f, fieldnames=["product_id", "name", "category", "unit_price"])
    writer.writeheader()
    writer.writerows(products)

# -----------------------------------------------------------
# Escribir CSV orders
# -----------------------------------------------------------
with open(os.path.join(LANDING_DIR, "orders.csv"), "w", newline="", encoding="utf-8") as f:
    writer = csv.DictWriter(f, fieldnames=["order_id", "customer_id", "product_id", "quantity", "order_ts", "status"])
    writer.writeheader()
    writer.writerows(orders)

print(f"Datos generados en {LANDING_DIR}/")
print(f"  customers.csv: {len(customers)} filas")
print(f"  products.csv: {len(products)} filas")
print(f"  orders.csv: {len(orders)} filas")