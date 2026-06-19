package com.ma.sms.android.data.model

data class DevisFourniture(
    val id: Long? = null,
    val element: String? = null,
    val position1: String? = null,
    val position2: String? = null,
    val type: String? = null,
    val categorie: String? = null,
    val sousReserve: Boolean = false
)

data class Devis(
    val id: Long? = null,
    val reference: String? = null,
    val etat: String? = null,
    val fournitures: List<DevisFourniture> = emptyList()
)
