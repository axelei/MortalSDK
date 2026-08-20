# Localización de paletas

## Formato CRAM

Una línea de paleta de Mega Drive contiene 16 palabras big-endian. Cada palabra usa el formato `0000 BBB0 GGG0 RRR0`; los demás bits deben ser cero. MortalSDK convierte cada canal de tres bits al intervalo RGB de 0 a 255 para las previews.

La coincidencia con este formato no basta para demostrar que 32 bytes sean una paleta. El comando `palette scan` exige además que exista al menos un valor de cuatro bytes dentro de la ROM que apunte al candidato y que haya seis colores distintos como mínimo. El resultado sigue siendo una lista de candidatos, no una prueba definitiva.

## Tablas confirmadas en Arcade Edition v2.0

### Tabla en `0x000BF4`

Contiene 14 slots de paleta y termina antes de los ceros de `0x000C2C`:

```text
19F6D8 19F6F8 19D126 19D146 19F9E2 19FA02 19FAB2
2028A0 199400 199420 19597A 19599A 2028C0 2028A0
```

Las direcciones aparecen también en una copia de la tabla alrededor de `0x34D786`. Varias tienen referencias adicionales desde código. Esto confirma que no son simples coincidencias del escáner.

El par `0x19597A`/`0x19599A` está asociado con los recursos que rodean el bloque RNC `0x1959BC`. Aplicar `0x19597A` a sus tiles produce colores coherentes de escenario; la segunda línea no debe aplicarse globalmente porque la selección de línea depende del mapa de tiles.

### Tabla en `0x007004`

Contiene 29 slots antes del código que comienza en `0x007078`. Apunta principalmente al banco comprendido entre `0x1DA3D4` y `0x1DA6B4`, con entradas repetidas y dos referencias a `0x23EA00`.

Esta tabla demuestra que no todas las paletas válidas tienen negro en el índice cero. Por ejemplo, `0x1DA574`, `0x1DA5B4`, `0x1DA5D4`, `0x1DA634` y `0x1DA694` son destinos directos aunque su primera palabra no sea `0x000`.

Por su cantidad, variantes y organización, este bloque es compatible con una tabla de paletas de personajes. La asociación exacta entre slot y luchador todavía debe confirmarse observando quién indexa la tabla durante la selección o la carga del combate.

### Otros grupos referenciados

- El banco `0x1A0000–0x1A04BF` contiene numerosas líneas de 32 bytes referenciadas desde rutinas en `0x012000–0x0169FF`.
- `0x2028A0`, `0x2028C0` y `0x2028E0` se reutilizan desde varias rutinas y parecen paletas comunes o de interfaz.
- `0x232C60`, `0x23EA00`, `0x2EDB60` y `0x34B700` tienen referencias directas y formato CRAM válido, pero su función concreta sigue pendiente.

## Resultado reproducible

Sobre la ROM estudiada, el filtro amplio encuentra 107 candidatos referenciados. La salida incluye:

```text
palette_XXXXXX.pal
palette_XXXXXX.png
palette-sheet.png
palette-references.csv
```

El número 107 no debe interpretarse como “107 paletas únicas usadas por el juego”: puede incluir subpaletas solapadas, tablas duplicadas o falsos positivos. Las dos tablas anteriores son el subconjunto confirmado actualmente.

## Siguiente comprobación

Para asociar correctamente colores y gráficos todavía hacen falta los mapas de tiles. Los atributos del mapa seleccionan una de las cuatro líneas de CRAM, además de prioridad y volteos. Una preview lineal con una única línea de 16 colores permite descartar combinaciones, pero no reconstruir por sí sola una pantalla completa.
