# MortalSDK
Extractor e insertor de bloques comprimidos y textos de Mortal Kombat (Mega Drive). Está escrito en Java 24 y preparado para compilarlo AoT con GraalVM.

¿Por qué en Java? Porque es el lenguaje que, en este momento, me da de comer. :)

## Uso:

### Extracción:

`MortalSDK x "mortal kombat.bin"`

En la carpeta `extracted` se generarán los bloques descomprimidos. Los gráficos están en formato tiles de Mega Drive, es decir, 4bpp linear. El texto estará en el nombre de la ROM más `.txt`.

### Inyección:

`MortalSDK i "mortal kombat.bin"`

Se generará un fichero nuevo con los recursos inyectados.

## Requisitos

Require `rnc_propack_x64.exe` en el mismo directorio. Se puede obtener de: https://github.com/lab313ru/rnc_propack_source/releases

Con pequeños ajustes se puede usar con otras ROMs y otros sistemas operativos. Añade un 'issue' si tienes alguna propuesta de cambio.

## Compilación

Para compilarlo, necesitas tener instalado Maven y GraalVM. Puedes encontrar más información en la [página oficial de GraalVM](https://www.graalvm.org/). Si no quieres o no necesitas compilación AoT, elimina dicha sección del `pom.xml`.

Sólo necesitas ejecutar: `mvn clean package`

## Cosas por hacer (no necesariamente en orden)

- Pasar la configuración a un fichero externo (caracteres de texto, rangos de extracción, tamaño mínimo de cadena, etc.)
- Dar soporte a más sistemas operativos
- Extraer paletas
- Mejorar la extracción de textos
- Programar mi propio extractor y compresor de RNC para no necesitar rnc_propack_x64.exe
- Internacionalizar los mensajes
- Crear tests unitarios
- Lanzar mejores alertas si hay inconsistencias y recuperación de errores

## Autoría y reconocimientos

Gracias a [Rael G. C.](https://github.com/raelgc) por la información que me faltaba sobre el formato de gráficos y esquema de compresión. 

By Krusher, licenciado bajo GPL 3. Por favor, consulta el fichero LICENSE.