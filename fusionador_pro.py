import re

# CONFIGURACION
FILE_TV = "lista_tv_nueva.m3u"
FILE_CINE = "lista_tecnomolly.m3u"
FILE_OUT = "lista_maestra.m3u"

# Patrones para detectar Series (S01E01, Cap 1, etc)
REGEX_SERIE = re.compile(r"(.*?)\s+(S\d+|T\d+|E\d+|Cap\.|Ep\.|Temporada)", re.IGNORECASE)

# Almacenamiento
tv_channels = set()
final_lines = ["#EXTM3U"]

print("🔄 Procesando Canales de TV...")
try:
    with open(FILE_TV, "r", encoding="utf-8", errors="ignore") as f:
        lines = f.readlines()
        for i in range(len(lines)):
            line = lines[i].strip()
            if line.startswith("#EXTINF"):
                # Extraemos nombre para evitar duplicados luego
                parts = line.split(",")
                name = parts[-1].strip().upper()
                tv_channels.add(name)
                
                # Forzamos que los canales tengan grupo "TV" si no lo tienen
                if "group-title=" not in line:
                    line = line.replace("#EXTINF:-1", '#EXTINF:-1 group-title="TV EN VIVO"')
                
                final_lines.append(line)
                # Agregamos la URL (siguiente linea)
                if i + 1 < len(lines):
                    final_lines.append(lines[i+1].strip())
except Exception as e:
    print(f"❌ Error leyendo TV: {e}")

print("🔄 Procesando Cine y Organizando Series...")
try:
    with open(FILE_CINE, "r", encoding="utf-8", errors="ignore") as f:
        lines = f.readlines()
        skip_next = False
        
        for i in range(len(lines)):
            if skip_next:
                skip_next = False
                continue
                
            line = lines[i].strip()
            if line.startswith("#EXTINF"):
                # Extraer datos
                parts = line.split(",")
                name_raw = parts[-1].strip()
                name_upper = name_raw.upper()
                
                # 1. ANTI-DUPLICADOS: Si ya está en TV, lo saltamos
                # (Solo si parece un canal de TV, para no borrar pelis con nombres iguales)
                is_tv_like = any(x in name_upper for x in ["TV", "CANAL", "NOTICIAS", "DEPORTES", "VIVO"])
                if is_tv_like and name_upper in tv_channels:
                    print(f"   ✂️ Eliminado duplicado: {name_raw}")
                    skip_next = True
                    continue
                
                # 2. ORGANIZADOR DE SERIES
                # Detectamos si es un capítulo
                match = REGEX_SERIE.search(name_raw)
                if match:
                    # Es una serie! Extraemos el nombre limpio (sin capitulo)
                    nombre_serie = match.group(1).strip(" -_")
                    # Creamos una CARPETA VIRTUAL para esta serie
                    new_group = f'group-title="SERIE: {nombre_serie}"'
                    
                    # Reemplazamos el grupo anterior
                    if "group-title=" in line:
                        line = re.sub(r'group-title="[^"]*"', new_group, line)
                    else:
                        line = line.replace("#EXTINF:-1", f'#EXTINF:-1 {new_group}')
                
                else:
                    # Es una Película
                    # Si no tiene grupo, le ponemos CINE
                    if "group-title=" not in line:
                        line = line.replace("#EXTINF:-1", '#EXTINF:-1 group-title="CINE"')
                    else:
                        # Si tiene grupo, le agregamos el prefijo CINE para que se ordene bien
                        # (Opcional, pero ayuda a separar CINE de TV en el menu)
                        if "group-title=\"CINE" not in line and "group-title=\"SERIE" not in line:
                             line = re.sub(r'group-title="([^"]*)"', r'group-title="CINE: \1"', line)

                final_lines.append(line)
                if i + 1 < len(lines):
                    final_lines.append(lines[i+1].strip())
                    skip_next = True

except Exception as e:
    print(f"❌ Error leyendo Cine: {e}")

# Guardar resultado
with open(FILE_OUT, "w", encoding="utf-8") as f:
    f.write("\n".join(final_lines))

print(f"✅ ¡Fusión Éxitosa! Lista final creada con {len(final_lines)//2} items.")
