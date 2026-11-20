package com.reloj.despertar.sound

data class SoundInfo(val id: String, val name: String, val description: String)

/** Lista de sonidos que genera la propia app (no hay archivos de audio) + la opción de un archivo del usuario. */
object Sounds {
    const val FILE = "file"
    const val DEFAULT = "amanecer"

    val presets = listOf(
        SoundInfo("amanecer", "Amanecer", "Arpegios suaves de Do mayor que se aceleran poco a poco. El recomendado."),
        SoundInfo("arpa", "Arpa", "Notas pulsadas, claras y cortas, sin ningún tono estridente."),
        SoundInfo("campanas", "Campanas", "Campanas largas y resonantes, muy pausadas."),
        SoundInfo("calido", "Cálido", "Registro grave y redondo, sin agudos molestos."),
        SoundInfo("bosque", "Bosque", "Pájaros suaves al amanecer sobre un fondo tenue."),
        SoundInfo("olas", "Olas", "Oleaje suave con alguna campana lejana."),
    )

    fun name(id: String): String =
        if (id == FILE) "Mi archivo" else presets.firstOrNull { it.id == id }?.name ?: "Amanecer"
}

/** Algo que suena con subida suave y se puede parar. Lo implementan el sintetizador y el reproductor de archivos. */
interface RingSound {
    fun start()
    fun stop()
    fun isAlive(): Boolean
}
