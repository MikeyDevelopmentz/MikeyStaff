rootProject.name = "SpearStaff"

includeBuild("../MikeyCore") {
    name = "mikey-core"
}

include("plugins:advanced-staff:paper")
include("plugins:advanced-staff:velocity")
