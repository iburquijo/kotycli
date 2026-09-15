"""Capa SILVER del pipeline medallion.

Lee Parquet de lake/bronze/, aplica transformaciones y escribe Parquet en lake/silver/<tabla>
modo overwrite. También genera un reporte de calidad en lake/silver/_quality_report.json.
"""
import json
import os

from pyspark.sql.functions import col, to_date, current_timestamp, input_file_name
from jobs.common import get_spark

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
LAKE_DIR = os.path.join(BASE_DIR, "..", "lake")


def main() -> None:
    spark = get_spark("medallion-silver")

    # --- Lectura de capas bronce ---
    orders_bronze_path = os.path.join(LAKE_DIR, "bronze", "orders")
    customers_bronze_path = os.path.join(LAKE_DIR, "bronze", "customers")
    products_bronze_path = os.path.join(LAKE_DIR, "bronze", "products")

    orders_df = spark.read.parquet(orders_bronze_path)
    customers_df = spark.read.parquet(customers_bronze_path)
    products_df = spark.read.parquet(products_bronze_path)

    # --- Transformaciones de ORDERS ---
    # 1. castear tipos
    orders_df = orders_df.withColumn("order_id", col("order_id").cast("int")) \
        .withColumn("customer_id", col("customer_id").cast("int")) \
        .withColumn("product_id", col("product_id").cast("int")) \
        .withColumn("quantity", col("quantity").cast("int")) \
        .withColumn("order_ts", col("order_ts").cast("timestamp"))

    # 2. dropDuplicates() -> cuenta duplicates_removed
    total_before_dedup = orders_df.count()
    deduped_df = orders_df.dropDuplicates()
    duplicates_removed = total_before_dedup - deduped_df.count()

    # 3. filtrar quantity no nulo y > 0 -> cuenta bad_quantity_removed
    # quantity ya fue casteado a int, los nulos/empty pueden haber pasado a null
    filtered_df = deduped_df.filter((col("quantity").isNotNull()) & (col("quantity") > 0))
    bad_quantity_removed = deduped_df.count() - filtered_df.count()

    # 4. left_semi join con customers por customer_id -> cuenta orphan_customer_removed
    joined_df = filtered_df.join(customers_df, "customer_id", "left_semi")
    orphan_customer_removed = filtered_df.count() - joined_df.count()

    # 5. añadir columna order_date = to_date(order_ts)
    final_orders_df = joined_df.withColumn("order_date", to_date(col("order_ts")))

    # Escritura de orders
    silver_orders_path = os.path.join(LAKE_DIR, "silver", "orders")
    final_orders_df.write.mode("overwrite").parquet(silver_orders_path)

    # --- Transformaciones de CUSTOMERS ---
    # castear signup_date a date y dropDuplicates
    customers_df = customers_df.withColumn("signup_date", col("signup_date").cast("date"))
    customers_deduped = customers_df.dropDuplicates()
    customers_out_count = customers_deduped.count()

    # Escritura de customers
    silver_customers_path = os.path.join(LAKE_DIR, "silver", "customers")
    customers_deduped.write.mode("overwrite").parquet(silver_customers_path)

    # --- Transformaciones de PRODUCTS ---
    # castear unit_price a double y dropDuplicates
    products_df = products_df.withColumn("unit_price", col("unit_price").cast("double"))
    products_deduped = products_df.dropDuplicates()
    products_out_count = products_deduped.count()

    # Escritura de products
    silver_products_path = os.path.join(LAKE_DIR, "silver", "products")
    products_deduped.write.mode("overwrite").parquet(silver_products_path)

    # --- Reporte de calidad ---
    quality_report = {
        "orders_in": total_before_dedup,
        "duplicates_removed": duplicates_removed,
        "bad_quantity_removed": bad_quantity_removed,
        "orphan_customer_removed": orphan_customer_removed,
        "orders_out": joined_df.count(),
    }

    report_path = os.path.join(LAKE_DIR, "silver", "_quality_report.json")
    with open(report_path, "w") as f:
        json.dump(quality_report, f)

    print(f"Silver pipeline completed. Reporte guardado en {report_path}")
    print(f"Calidad: {json.dumps(quality_report, indent=2)}")

    spark.stop()


if __name__ == "__main__":
    main()