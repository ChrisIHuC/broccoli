package nl.knaw.huc.broccoli.core

import nl.knaw.huc.broccoli.config.TierConfiguration
import nl.knaw.huc.broccoli.service.anno.AnnoRepo
import nl.knaw.huc.broccoli.service.text.TextRepo

class Project(
    val name: String,
    val tiers: List<TierConfiguration>,
    val textRepo: TextRepo,
    val annoRepo: AnnoRepo,
)
