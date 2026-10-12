/** JSON model used by the legacy voxel exporter. */
class VoxelModel {
    var dimension: MutableList<VoxelDimensions> = mutableListOf()
    var voxels: MutableList<VoxelEntry> = mutableListOf()

    class VoxelEntry {
        var id: String? = null
        var x: Int = 0
        var y: Int = 0
        var z: Int = 0
        var red: Int = 0
        var green: Int = 0
        var blue: Int = 0
    }

    class VoxelDimensions {
        var width: Int = 0
        var height: Int = 0
        var depth: Int = 0
    }
}
