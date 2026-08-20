# Tabla y reemplazo de samples

Este documento describe únicamente el formato observado en *Mortal Kombat Arcade Edition v2.0*. No debe asumirse que otras revisiones de la ROM utilicen la misma dirección, cantidad de entradas o frecuencia de reproducción.

## Entrada de tabla

Cada entrada ocupa ocho bytes:

| Posición | Tamaño | Significado |
| --- | ---: | --- |
| `+0` | 1 byte | ID del sample |
| `+1` | 3 bytes | Offset absoluto dentro de la ROM, big-endian |
| `+4` | 2 bytes | Longitud del PCM en bytes, big-endian |
| `+6` | 2 bytes | Flags conservados sin modificación |

Una longitud cero representa una entrada vacía. Para extraer una entrada no vacía, `offset + longitud` debe quedar dentro de la ROM.

La configuración incluida sitúa la tabla en `0x281C50` y lee 116 entradas. La entrada ID `0x63` apunta fuera de una ROM de 4 MiB; se muestra como inválida en `sample list` y no se extrae ni se acepta como destino de reemplazo.

## Conversión PCM/WAV

Los datos de la ROM se interpretan como PCM signed de 8 bits. WAV representa PCM de 8 bits como unsigned, por lo que la conversión cambia el bit de signo de cada muestra mediante XOR con `0x80`. Esta operación es reversible byte por byte.

El WAV aceptado debe tener:

- una sola pista;
- PCM unsigned de 8 bits;
- la frecuencia exacta indicada por `pcmSampleRate`;
- entre 1 y 65535 muestras para una entrada recolocada.

No se remuestrea, mezcla ni normaliza audio automáticamente. Un editor externo debe exportar directamente en el formato requerido.

## Reemplazo de longitud fija

El modo general `i` busca el nombre completo exportado, por ejemplo:

```text
sample_01_1e0038_2400_006a.wav
```

El nombre codifica ID, offset, longitud y flags en hexadecimal. Este flujo escribe sobre la ubicación original y exige exactamente la longitud original. Es apropiado cuando el WAV editado conserva el número de muestras.

## Reemplazo con recolocación

La GUI y `sample replace` aceptan otra longitud. Para cada sample modificado:

1. se busca su ID en la tabla original;
2. se reserva espacio alineado a dos bytes dentro de `spaceRanges`;
3. se copia el PCM convertido;
4. se escribe el nuevo offset de 24 bits;
5. se escribe la nueva longitud de 16 bits;
6. se conservan el ID y los flags;
7. al terminar se recalcula el checksum.

El orden de asignación es determinista: rangos por dirección ascendente y reemplazos por ID ascendente. Los rangos fuera de la ROM o solapados se rechazan.

## Restricciones del asignador

`spaceRanges` no es un mapa dinámico del espacio usado por el juego. Es una lista proporcionada por quien prepara la configuración. El programa no puede distinguir de forma fiable entre relleno y datos que solo se leen en situaciones concretas.

El estado de ocupación tampoco se almacena en la ROM ni en un proyecto separado. Por eso:

- deben enviarse todos los reemplazos juntos;
- debe usarse como entrada la misma ROM base limpia;
- no debe ejecutarse una segunda recolocación sobre una salida anterior con los mismos rangos;
- los rangos deben revisarse de nuevo para cada versión de la ROM.

## Comprobación recomendada

Después de generar una ROM:

1. confirme que su tamaño no ha cambiado;
2. ejecute `sample list` sobre la salida y revise los nuevos punteros;
3. vuelva a extraer los samples y compare el PCM esperado;
4. pruebe cada sonido modificado en el contexto real del juego;
5. conserve el hash de la ROM base y de la salida.

La reproducción en la GUI solo permite escuchar los bytes con la frecuencia configurada. No demuestra por sí sola que flags, temporización y selección del sample sean correctos durante la ejecución del juego.
