try:
    with open("lista_temp.m3u", "r", encoding="utf-8", errors="ignore") as f_in,          open("lista_final.m3u", "w", encoding="utf-8") as f_out:
        f_out.write("#EXTM3U\n")
        guardar = False
        count = 0
        for line in f_in:
            l = line.strip()
            if l.startswith("#EXTINF"):
                # Si NO tiene palabras de TV en vivo, lo guardamos
                upper = l.upper()
                if not any(x in upper for x in ["TV EN VIVO", "NOTICIAS", "DEPORTES", "24/7"]):
                    guardar = True
                    f_out.write(l + "\n")
                    count += 1
                else:
                    guardar = False
            elif l.startswith("#"):
                continue
            elif guardar and l:
                f_out.write(l + "\n")
                guardar = False
    print(f"🎬 Se encontraron {count} películas/series.")
except Exception as e:
    print(f"Error: {e}")
