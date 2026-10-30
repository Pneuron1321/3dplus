extends SceneTree
## Проверка без окна: godot --headless --path <проект> --script res://check_ragdoll.gd
## Переменная окружения GLB — какой файл проверять (по умолчанию res://pixel-mech-rig-lite.glb).
## Печатает кости, сетки и материалы, собирает рэгдолл скриптом pixel_mech_ragdoll.gd,
## роняет фигурку на пол и печатает, где оказались голова и таз.

var rig: Node3D
var frames := 0
var start := {}

func _initialize() -> void:
	pass

func _physics_process(_delta: float) -> bool:
	frames += 1
	if frames == 1:
		var file: String = OS.get_environment("GLB") if OS.get_environment("GLB") != "" else "res://pixel-mech-rig-lite.glb"
		var floor := StaticBody3D.new()
		var fs := CollisionShape3D.new()
		var box := BoxShape3D.new()
		box.size = Vector3(4, 0.2, 4)
		fs.shape = box
		floor.add_child(fs)
		floor.position = Vector3(0, -0.1, 0)
		root.add_child(floor)
		rig = Node3D.new()
		rig.set_script(load("res://pixel_mech_ragdoll.gd"))
		rig.mech_scene = load(file)
		rig.joint_limits = OS.get_environment("LIMITS") != "0"
		rig.self_collision = OS.get_environment("SELFCOLL") == "1"
		root.add_child(rig)
		var sk: Skeleton3D = rig.skeleton
		var names := []
		for i in sk.get_bone_count():
			names.append(sk.get_bone_name(i))
		print("файл ", file, " · костей ", sk.get_bone_count(), ": ", ", ".join(names))
		for mi in sk.find_children("*", "MeshInstance3D", true, false):
			var mat = mi.mesh.surface_get_material(0)
			print("сетка ", mi.name, " · вершин ", mi.mesh.surface_get_array_len(0), " · цвета вершин ", mat.vertex_color_use_as_albedo, " · skin ", mi.skin != null)
		print("физических костей ", rig.simulator.get_child_count())
		return false
	if frames == 5:
		for pb in rig.simulator.get_children():
			start[pb.name] = pb.global_position
		rig.set_ragdoll(true)
		return false
	if frames == 240:
		var ok := true
		for n in ["PB_Head", "PB_Hips", "PB_LeftFoot", "PB_RightLowerArm"]:
			var pb: PhysicalBone3D = rig.simulator.get_node(n)
			var p := pb.global_position
			ok = ok and p.is_finite() and p.y > -0.05 and p.y < 0.35
			print(n, ": ", start[n], " → ", p)
		var head: Vector3 = rig.simulator.get_node("PB_Head").global_position
		print("ИТОГ рэгдолл: ", "упал и лежит на полу" if ok and head.y < 0.12 else "ПРОБЛЕМА", " (голова на высоте ", snapped(head.y, 0.001), " м)")
		return true
	return false
