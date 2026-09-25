# BlackBox

La referencia completa de funciones, señales, análisis, límites, configuración y procedimientos está en
[`BLACKBOX-GUIA-COMPLETA.md`](BLACKBOX-GUIA-COMPLETA.md). La configuración incluida también está comentada por
bloques y conserva las mismas claves compatibles con versiones anteriores.

BlackBox es un plugin de telemetria y diagnostico compatible con **Paper 1.21.4–26.3**. Registra el estado del servidor sin hacer IO en el hilo principal, conserva historico por minutos, horas y dias, escanea los chunks cargados por lotes y genera informes Markdown con evidencias, hipotesis y acciones recomendadas.

El JAR conserva bytecode Java 21 para funcionar en Paper 1.21.4. Paper 26.3 requiere Java 25 o superior. Gradle usa las toolchains instaladas o provisionadas para compilar y comprobar ambas APIs:

```powershell
.\gradlew.bat clean build
```

La tarea `check` compila el codigo una vez contra Paper 1.21.4 y otra contra Paper 26.3, ademas de ejecutar los tests.

El JAR queda en `build/libs/BlackBox-0.6.0.jar`. Copialo a `plugins/` y reinicia Paper. BlackBox carga en fase
`STARTUP`, registra automaticamente durante toda la ejecucion y, en cada apagado o reinicio normal, crea
`BlackBox-session-report.pdf` con la sesion completa. Los datos se escriben en `plugins/BlackBox/telemetry/`
y los informes en `plugins/BlackBox/reports/`.

La telemetria se rota en segmentos de 5 minutos y los segmentos cerrados se comprimen con GZIP. El directorio
`telemetry/` tiene una cuota dura de 256 MiB por defecto: al alcanzarla se eliminan primero los segmentos mas
antiguos. Cada stream admite como maximo 1.000 filas por minuto para que una granja, command block o plugin con
eventos masivos no pueda llenar el disco. Estos limites se configuran con `storage.max-telemetry-mib`,
`storage.segment-minutes`, `storage.max-rows-per-stream-minute` y `storage.max-row-chars`.
Los perfiles JFR tienen una cuota independiente de 256 MiB y los informes de 256 MiB; también conservan primero
lo más reciente mediante `storage.max-profiles-mib` y `storage.max-reports-mib`.

Durante el arranque, BlackBox conecta primero un monitor al log en vivo y despues sincroniza retroactivamente
hasta 64 MiB de `logs/latest.log`, desde el comienzo de la sesion actual. Captura `WARN`, `ERROR`, `SEVERE` y
`FATAL`, reconstruye stacks multilinea y clasifica el tipo probable (configuracion, codigo,
dependencia/version, archivos/permisos, base de datos, red o recursos JVM), el origen, la causa y una posible
solucion sin inundar la consola. El analisis aparece en los hallazgos del PDF y completo en
`startup-diagnostics.csv`; el registro fuente se conserva en `plugins/BlackBox/startup-diagnostics.log`. Los valores invalidos,
fuera de rango y las claves desconocidas de `config.yml` tambien indican la clave y, cuando puede localizarse,
la linea exacta. Un YAML mal formado se diagnostica y no se sobrescribe.

## Comandos

- `/blackbox status`: TPS, MSPT, p95 de tick, CPU, heap, jugadores, chunks y ultimo escaneo.
- `/blackbox scan`: inicia inmediatamente un escaneo por lotes de todos los chunks cargados.
- `/blackbox report 30m|6h|2d`: genera un PDF profesional y sus anexos CSV en segundo plano.
- `/blackbox profile 60`: genera un perfil JFR y un analisis inicial de plugins/metodos, GC, archivos y sockets.

Todos requieren `blackbox.admin`, concedido a operadores por defecto.

## Datos capturados

- TPS 1/5/15 min, MSPT medio, p50/p95/p99/max de los ticks recientes.
- CPU del proceso/sistema, heap, GC, hilos, espacio de disco, jugadores y chunks cargados.
- RAM fisica, swap, memoria no-heap/directa, descriptores abiertos, deadlocks y cola de telemetria.
- Historial persistente de arranques/apagados y de plugins/versiones presentes en cada sesion.
- Comparativa automatica con el periodo anterior de igual duracion y contexto de plugins anadidos, retirados o actualizados.
- Cronologia de incidentes con severidad, momento y localizacion; los hotspots cambian de color segun su umbral.
- Episodios causa-efecto: inicio, peor punto, recuperacion, duracion, MSPT antes/durante/despues, causa probable,
  evidencias coincidentes, localizacion, nivel de confianza y una prueba concreta para validar la solucion.
- Graficas temporales de CPU del proceso y sistema, heap, RAM fisica, hilos y disco libre, ademas de TPS y p95.
- E/S de archivo y socket medida por cada perfil JFR, con bytes, tiempo total, operacion mas lenta y eventos de GC.
- Para cada chunk localizado muestra el centro X/Z y un comando `/tp @s X ~ Z` copiable.
- Analisis por plugin que combina tareas, listeners y presencia en stacks JFR sin presentar correlacion como causalidad.
- Cargas de chunks, spawns, muertes, redstone, hoppers, pistones, explosiones y actividad de bloques.
- Ping, protocolo, marca del cliente, locale, distancias de vista/simulacion, chunks enviados y eventos de conexion/resource pack.
- Por chunk: entidades por tipo, items, living entities, item frames, jugadores, block entities por tipo, hoppers, spawners, command blocks y carga forzada.
- Ciclo de carga por chunk: alta, descarga, guardado, nueva generacion, tiempo cargado observado, tickets de plugins y coste del propio escaneo.
- Ciclo de vida por entidad: UUID, tipo, motivo de spawn, causa de retirada, origen, posicion, tiempo de vida, ticking, persistencia, pasajeros y jugadores que la reciben.
- Hotspots por mundo/chunk para cargas, spawns, muertes, redstone, hoppers, pistones, explosiones, roturas y colocaciones.
- Censo offline de todos los archivos Anvil `.mca`: enumera cada chunk de terreno, entidades y POI sin cargarlo; extrae entidades, block entities y ticks de bloque/fluidos serializados y valida las cabeceras.
- Warnings, errores y stacks emitidos por plugins mediante el sistema de logging de Java.
- Avisos de ticks atrasados (`Can't keep up`), watchdogs y crash reports del servidor con causa causal,
  primer frame atribuible y pasos de verificacion. Los informes completos quedan intactos en `crash-reports/`.
- Dependencias obligatorias ausentes/desactivadas y plugins que terminaron el arranque desactivados.
- Plugins, versiones, dependencias, comandos, permisos, aliases, mundos, datapacks y resource pack configurado.
- Tareas sincronas/asincronas pendientes y listeners registrados por plugin.
- Archivos de configuracion y assets con tamano, fecha, SHA-256 y valores sanitizados. Contrasenas, tokens, credenciales, claves privadas y direcciones se sustituyen por `<redacted>`.
- Contenido estadistico de ZIPs de resource/data packs: modelos, texturas, blockstates, funciones, tags, recetas y loot tables.

Las direcciones de jugadores se guardan con hash salado por defecto. Puede elegirse `hash`, `plain` u `off` en `config.yml`.

## Limites reales

Paper no recibe el FPS, GPU, mods, shaders ni memoria del cliente. Eso requeriria un mod cliente complementario. Las particulas son paquetes transitorios y no existe un inventario global retrospectivo. La API publica tampoco ofrece CPU exacta por entidad/chunk ni identifica que plugin origino cada llamada; BlackBox conserva evidencias, calcula correlaciones y usa JFR bajo demanda para atribuir trabajo mediante stacks reales. Un chunk descargado no ejecuta ticks; el censo offline describe lo que conserva en disco, no inventa actividad mientras estuvo inactivo.

El muestreo normal esta disenado para ser acotado: los chunks se reparten entre ticks, la escritura usa una cola asincrona y los informes/hashes se generan fuera del hilo principal. Los intervalos, lotes, umbrales y retencion se configuran en `plugins/BlackBox/config.yml`.

El idioma predeterminado es `en_US`. Para usar español, cambia `language` a `es_ES` en `config.yml`. Los textos editables están en `lang/en_US.yml` y `lang/es_ES.yml`; ambos usan claves YAML estables para que MrDinoBot pueda traducir sus valores. El idioma seleccionado se aplica a comandos, logs generados, PDF, Markdown y análisis JFR.

Cuando `profiling.automatic.enabled` esta activo, varias muestras consecutivas por debajo del TPS configurado o por
encima del p95 de tick inician un JFR corto. `profiling.automatic.cooldown-minutes` evita capturas repetidas durante
el mismo incidente. El PDF incorpora indice, marcadores navegables, recursos JVM/sistema, comparativa historica,
un glosario de una pagina y una referencia de siglas en cada pie. Los anexos nuevos son
`performance-episodes.csv`, `profile-io.csv` y `crash-analysis.csv`, ademas de `incidents.csv`,
`session-history.csv`, `plugin-analysis.csv` y `profiles.csv`.

El analizador de episodios compara ventanas inmediatamente anteriores, durante el problema y posteriores. Combina
CPU, RAM, heap, GC, jugadores, chunks, entidades, eventos y hotspots geograficos, warnings y stacks de perfiles JFR.
Una confianza alta requiere evidencia directa de log o JFR; una confianza media combina varias senales; una baja
se presenta como hipotesis reproducible. De este modo el informe no convierte una coincidencia estadistica en una
acusacion falsa contra un plugin.

Los CSV del reporte usan tablas normalizadas y columnas independientes. Las relaciones de uno a muchos se
exportan en archivos propios: aliases de comandos, autores y dependencias de plugins, entradas de configuracion,
features de datapacks, plugins/datapacks por sesion y tipos o tickets por chunk. Los anexos geograficos incluyen
el centro del chunk y un comando `/tp` listo para usar.

BlackBox analiza los crash reports del servidor que esten en `crash-reports/`. Para analizar un crash de cliente,
copia el `.txt` entregado por el jugador a `plugins/BlackBox/client-crash-reports/` y genera otro informe. El
protocolo de Minecraft no envia automaticamente el informe, FPS, GPU, mods ni memoria del cliente al servidor.
