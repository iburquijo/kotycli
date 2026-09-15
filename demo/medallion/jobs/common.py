"""Módulo compartido para el pipeline medallion.
Proporciona la función get_spark() para crear la SparkSession.
"""
from pyspark.sql import SparkSession


def get_spark(app_name: str) -> SparkSession:
    """Crea y devuelve una SparkSession local[2] con UI deshabilitado.

    Args:
        app_name: Nombre de la aplicación Spark.

    Returns:
        SparkSession configurada.
    """
    return (
        SparkSession.builder
        .master("local[2]")
        .appName(app_name)
        .config("spark.ui.enabled", "false")
        .getOrCreate()
    )