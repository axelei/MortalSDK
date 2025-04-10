# MortalSDK
Extractor e insertor de bloques comprimidos y textos de Mortal Kombat (Mega Drive). Está escrito en Java 24 y preparado para compilarlo AoT con GraalVM. Puede servir para otros juegos, en particular para los que usan compresión RNC.

¿Por qué en Java? Porque es el lenguaje que, en este momento, me da de comer. :)

## Uso:

### Extracción:

`MortalSDK x "mortal kombat.bin" [configuracion.properties]`

En la carpeta `extracted` se generarán los bloques descomprimidos. Los gráficos están en formato tiles de Mega Drive, es decir, 4bpp linear. El texto estará en el nombre de la ROM más `.txt`. Si se especifica `configuracion.properties` se usará esta.

En la carpeta `configs` hay un ejemplo de configuración que estoy usando para mi proyecto personal.

### Inyección:

`MortalSDK i "mortal kombat.bin" [configuracion.properties]`

Se generará un fichero nuevo con los recursos inyectados. Si se especifica `configuracion.properties` se usará esta.

## Requisitos

Require `rnc_propack_x64.exe` u otra compilación para extraer/inyectar bloques comprimidos RNC. Se puede obtener de: https://github.com/lab313ru/rnc_propack_source/releases

Con pequeños ajustes en la configuración se puede usar con otras ROMs y otros sistemas operativos. Añade un 'issue' si tienes alguna propuesta de cambio.

## Compilación

Para compilarlo, necesitas tener instalado Maven y GraalVM. Puedes encontrar más información en la [página oficial de GraalVM](https://www.graalvm.org/). Si no quieres o no necesitas compilación AoT, elimina dicha sección del `pom.xml`.

Sólo necesitas ejecutar: `mvn clean package`. En la carpeta `dist` tendrás el resultado.

## Cosas por hacer (no necesariamente en orden)

- Extraer paletas
- Mejorar la extracción de textos
- Programar mi propio extractor y compresor de RNC para no necesitar rnc_propack_x64.exe
- Internacionalizar los mensajes
- Crear tests unitarios
- Lanzar mejores alertas si hay inconsistencias y recuperación de errores

## Autoría y reconocimientos

Gracias a [Rael G. C.](https://github.com/raelgc) por la información que me faltaba sobre el formato de gráficos y esquema de compresión. 

By Krusher, licenciado bajo GPL 3. Por favor, consulta el fichero LICENSE.