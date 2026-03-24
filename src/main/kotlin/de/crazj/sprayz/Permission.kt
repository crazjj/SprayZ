package de.crazj.sprayz

import org.bukkit.permissions.PermissionDefault

enum class Permission(sub: String, val permissionDefault: PermissionDefault, parent: Permission?) {
    SPRAYZ("sprayz", PermissionDefault.OP, null),
    SPRAYZ_USE("use", PermissionDefault.TRUE, SPRAYZ),
    SPRAYZ_COMMAND_MAP("command.map", PermissionDefault.OP, SPRAYZ),
    REFRESH("refresh", PermissionDefault.OP, SPRAYZ), ;

    var full: String

    init {
        full = if (parent != null) parent.full + '.' + sub else sub
    }
}