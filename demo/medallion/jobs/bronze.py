"""Capa BRONCE del pipeline medallion.

Lee los CSV de landing/ (todo string, sin casteo), agrega columnas de trazabilidad
y escribe Parquet en lake/bronze/<tabla>.
"""
import os

from pyspark.sql.functions import current_timestamp, input_file_name
from jobs.common import get_spark

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
LAKING_DIR = os.path.join(BASE_DIR, "..", "landing")
LAKE_DIR = os.path.join(BASE_DIR, "..", "lake")

TABLES = ["customers", "products", "orders"]


def main() -> None:
    spark = get_spark("medallion-bronze")

    for table_name in TABLES:
        csv_path = os.path.join(LAKING_DIR, f"{table_name}.csv")
        df = spark.read.csv(csv_path, header=True, inferSchema=False)

        # Columnas de trazabilidad
        df = df.withColumn("_ingested_at", current_timestamp()) \
            .withColumn("_source_file", input_file_name())

        # Asegurar que todas las columnas originales sean strings
        for col_name in df.columns:
            if col_name not in ("_ingested_at", "_source_file"):
                df = df.withColumn(col_name, df[col_name].cast("string"))

        # Escribir en Parquet modo overwrite
        bronze_path = os.path.join(LAKE_DIR, "bronze", table_name)
        df.write.mode("overwrite").parquet(bronze_path)

        # Contar filas e imprimir
        count = df.count()
        print(f"{table_name}: {count} filas")

    spark.stop()


if __name__ == "__main__":
    main()
