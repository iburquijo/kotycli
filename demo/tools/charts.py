"""Gráficas de la corrida, para la documentación.

Esto NO es parte del proyecto medallion: el pipeline lo escribió el agente en medallion/jobs/.
Esto lo escribí yo (Claude) para documentar qué pasó, y lee lo que el pipeline dejó en disco.
"""
import json
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parent.parent
LAKE = ROOT / "medallion" / "lake"
OUT = ROOT / "screenshots"

TINTA = "#1b1b1f"
GRIS = "#8a8a94"
AZUL = "#4c78a8"
ROJO = "#c1666b"


def estilo(ax):
    ax.spines[["top", "right"]].set_visible(False)
    ax.spines[["left", "bottom"]].set_color(GRIS)
    ax.tick_params(colors=TINTA, labelsize=9)
    ax.yaxis.grid(True, color="#e6e6ea", linewidth=0.8)
    ax.set_axisbelow(True)


def embudo_calidad(report: dict) -> None:
    """Cuántas filas pierde orders en cada regla de la capa silver.

    El eje va recortado a propósito: las pérdidas (50, 150, 0) son dos órdenes de magnitud
    más pequeñas que el total, y a escala completa no se vería ninguna.
    """
    pasos = [
        ("bronze\n(entrada)", report["orders_in"], AZUL),
        ("duplicados", -report["duplicates_removed"], ROJO),
        ("quantity\nnula o <= 0", -report["bad_quantity_removed"], ROJO),
        ("customer\nhuérfano", -report["orphan_customer_removed"], ROJO),
        ("silver\n(salida)", report["orders_out"], AZUL),
    ]
    suelo = min(report["orders_out"], report["orders_in"]) - 250

    fig, ax = plt.subplots(figsize=(8.5, 4.6))
    acumulado = report["orders_in"]
    for i, (etiqueta, valor, color) in enumerate(pasos):
        if color is AZUL:
            ax.bar(i, valor - suelo, bottom=suelo, color=color, width=0.6)
            ax.text(i, valor + 22, f"{valor:,}".replace(",", "."), ha="center", fontsize=10, color=TINTA)
        else:
            caida = -valor
            ax.bar(i, max(caida, 4), bottom=acumulado - caida, color=color, width=0.6)
            ax.plot([i - 0.5, i + 0.3], [acumulado, acumulado], color=GRIS, lw=0.8, ls=":")
            ax.text(i, acumulado + 22, f"-{caida}" if caida else "0", ha="center", fontsize=10,
                    color=ROJO if caida else GRIS)
            acumulado -= caida
    ax.set_xticks(range(len(pasos)))
    ax.set_xticklabels([p[0] for p in pasos])
    ax.set_ylim(suelo, report["orders_in"] + 120)
    ax.set_ylabel("filas de orders")
    ax.set_title("Capa silver: qué descarta cada regla de calidad", fontsize=12.5, color=TINTA, pad=14)
    ax.text(0.5, -0.24, "Eje recortado: las pérdidas son ~3% del total.", transform=ax.transAxes,
            ha="center", fontsize=8.5, color=GRIS)
    estilo(ax)
    fig.tight_layout()
    fig.savefig(OUT / "02-calidad-silver.png", dpi=170)
    print("screenshots/02-calidad-silver.png")


def filas_por_capa() -> None:
    """Filas por tabla en cada capa: se ve que solo orders pierde filas al limpiar."""
    from pyspark.sql import SparkSession
    spark = (SparkSession.builder.master("local[2]").appName("charts")
             .config("spark.ui.enabled", "false").getOrCreate())
    spark.sparkContext.setLogLevel("ERROR")
    tablas = ["customers", "products", "orders"]
    datos = {capa: [spark.read.parquet(str(LAKE / capa / t)).count() for t in tablas]
             for capa in ("bronze", "silver")}
    spark.stop()

    fig, ax = plt.subplots(figsize=(8, 4.2))
    x = range(len(tablas))
    ax.bar([i - 0.19 for i in x], datos["bronze"], width=0.36, label="bronze", color=GRIS)
    ax.bar([i + 0.19 for i in x], datos["silver"], width=0.36, label="silver", color=AZUL)
    for i, t in enumerate(tablas):
        for desp, capa in ((-0.19, "bronze"), (0.19, "silver")):
            ax.text(i + desp, datos[capa][i] * 1.04, f"{datos[capa][i]:,}".replace(",", "."),
                    ha="center", fontsize=8.5, color=TINTA)
    ax.set_xticks(list(x))
    ax.set_xticklabels(tablas)
    ax.set_yscale("log")
    ax.set_ylabel("filas (escala log)")
    ax.set_title("Filas por tabla y capa", fontsize=12, color=TINTA, pad=14)
    ax.legend(frameon=False, fontsize=9)
    estilo(ax)
    fig.tight_layout()
    fig.savefig(OUT / "03-filas-por-capa.png", dpi=170)
    print("screenshots/03-filas-por-capa.png")


if __name__ == "__main__":
    OUT.mkdir(exist_ok=True)
    embudo_calidad(json.loads((LAKE / "silver" / "_quality_report.json").read_text()))
    filas_por_capa()
