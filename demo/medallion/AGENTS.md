# Proyecto: ETL medallion en PySpark local

Pipeline de ejemplo con arquitectura medallion (bronze -> silver -> gold) sobre PySpark en local.

## Entorno

- El intérprete es `../.venv/bin/python`. **Nunca uses `python3` ni `pip` del sistema**: pyspark solo está en ese venv.
- Spark en modo `local[2]`. Formato de tablas: Parquet en el directorio `lake/`.
- Para ejecutar algo: `../.venv/bin/python -m jobs.<modulo>` desde la raíz del proyecto.

## Reglas

- Código Python 3.11, sin dependencias fuera de pyspark, pandas y matplotlib.
- Cada capa es un módulo en `jobs/`: `bronze.py`, `silver.py`, `gold.py`.
- Nada de notebooks. Scripts ejecutables y deterministas.
- Comentarios y textos en castellano; identificadores en inglés.
