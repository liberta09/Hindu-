package com.liberta09

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class HintFilmIzlePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(HintFilmIzle())
    }
}
