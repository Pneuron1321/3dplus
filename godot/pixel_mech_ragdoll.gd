extends Node3D
## PIXEL MECH — рэгдолл для Godot 4.3+ из скелетного GLB (export/pixel-mech-rig.glb или -lite.glb).
##
## Как пользоваться: положи GLB в проект, добавь в сцену Node3D с этим скриптом и укажи mech_scene.
## Скрипт сам подгонит коробки коллизий под кирпичи каждой кости (по вершинам сетки), создаст
## PhysicalBoneSimulator3D с физическими костями и ограничениями суставов. set_ragdoll(true) —
## фигурка обмякает и падает; set_ragdoll(false) — возвращается в позу скелета.
## По умолчанию пробел / Enter (действие ui_accept) переключает рэгдолл.

@export var mech_scene: PackedScene
@export var ragdoll_on_start := false
@export var toggle_with_ui_accept := true
@export var total_mass := 1.2          ## кг на всю фигурку (реальная — ~0.3 кг, но лёгкие тела дрожат)
@export var joint_limits := true       ## ограничения суставов по таблице SPAN
## столкновения деталей фигурки между собой (кроме соседей по суставу). Выключено по умолчанию:
## у маленькой фигурки встроенный GodotPhysics с ними неустойчив; с Jolt можно включать
@export var self_collision := false

var skeleton: Skeleton3D
var simulator: PhysicalBoneSimulator3D
var ragdoll_on := false

## предельный угол сустава (градусы) по суффиксу имени кости
const SPAN := {
	"Hips": 0.0, "Spine": 12.0, "Chest": 12.0, "Neck": 20.0, "Head": 25.0,
	"UpperArm": 70.0, "LowerArm": 55.0, "UpperLeg": 50.0, "LowerLeg": 45.0, "Foot": 25.0
}

func _ready() -> void:
	if mech_scene == null:
		push_error("PIXEL MECH: укажи mech_scene (export/pixel-mech-rig-lite.glb)")
		return
	var mech := mech_scene.instantiate()
	add_child(mech)
	skeleton = _find_skeleton(mech)
	if skeleton == null:
		push_error("PIXEL MECH: в сцене нет Skeleton3D — нужен файл pixel-mech-rig*.glb")
		return
	simulator = PhysicalBoneSimulator3D.new()
	simulator.name = "Ragdoll"
	skeleton.add_child(simulator)
	var boxes := fit_boxes()
	var volume := 0.0
	for b in boxes.values(): volume += b.size.x * b.size.y * b.size.z
	var order: Array = boxes.keys()             # от корня к конечностям: родитель раньше ребёнка
	order.sort_custom(func(a, b): return skeleton.find_bone(a) < skeleton.find_bone(b))
	for bone_name in order:
		_add_physical_bone(bone_name, boxes[bone_name], volume)
	# соседи по суставу (родитель — ребёнок) не сталкиваются никогда: их коробки перекрываются у шарниров;
	# без self_collision детали вообще не сталкиваются друг с другом — только с полом и миром
	var pbs := simulator.get_children()
	var by_bone := {}
	for pb in pbs:
		by_bone[skeleton.find_bone(pb.bone_name)] = pb
	for bi in by_bone.keys():
		var parent := skeleton.get_bone_parent(bi)
		while parent >= 0 and not by_bone.has(parent):
			parent = skeleton.get_bone_parent(parent)
		if parent >= 0:
			by_bone[bi].add_collision_exception_with(by_bone[parent])
	if not self_collision:
		for a in pbs:
			for b in pbs:
				if a != b:
					a.add_collision_exception_with(b)
	if ragdoll_on_start:
		set_ragdoll.call_deferred(true)

func _unhandled_input(event: InputEvent) -> void:
	if toggle_with_ui_accept and event.is_action_pressed("ui_accept"):
		set_ragdoll(not ragdoll_on)

func set_ragdoll(on: bool) -> void:
	ragdoll_on = on
	if on:
		simulator.physical_bones_start_simulation()
	else:
		simulator.physical_bones_stop_simulation()
		for i in skeleton.get_bone_count():
			skeleton.reset_bone_pose(i)

## Коробка каждой кости — по вершинам, жёстко привязанным к ней (в координатах покоя кости).
func fit_boxes() -> Dictionary:
	var boxes := {}
	for mi in skeleton.find_children("*", "MeshInstance3D", true, false):
		var mesh: Mesh = mi.mesh
		for s in mesh.get_surface_count():
			var arr := mesh.surface_get_arrays(s)
			var verts: PackedVector3Array = arr[Mesh.ARRAY_VERTEX]
			var bones: PackedInt32Array = arr[Mesh.ARRAY_BONES]
			if bones.is_empty():
				continue
			var step := bones.size() / verts.size()
			var to_skel: Transform3D = skeleton.global_transform.affine_inverse() * mi.global_transform
			for i in verts.size():
				var bi := bones[i * step]
				var local: Vector3 = skeleton.get_bone_global_rest(bi).affine_inverse() * (to_skel * verts[i])
				var n := skeleton.get_bone_name(bi)
				if boxes.has(n):
					boxes[n] = boxes[n].expand(local)
				else:
					boxes[n] = AABB(local, Vector3.ZERO)
	for n in boxes.keys():                      # кости без своей детали (Root, Hand, Toes) не нужны
		if not SPAN.has(n) and not SPAN.has(_suffix(n)):
			boxes.erase(n)
	return boxes

func _add_physical_bone(bone_name: String, box: AABB, volume: float) -> void:
	var pb := PhysicalBone3D.new()
	pb.name = "PB_" + bone_name
	pb.bone_name = bone_name
	var size := box.size.max(Vector3(0.004, 0.004, 0.004))
	pb.mass = max(0.05, total_mass * size.x * size.y * size.z / max(volume, 1e-9))
	pb.friction = 0.8
	var cs := CollisionShape3D.new()
	var shape := BoxShape3D.new()
	shape.size = size * 0.92                  # чуть меньше детали — соседи не цепляются друг за друга
	cs.shape = shape
	cs.position = box.get_center()
	pb.add_child(cs)
	simulator.add_child(pb)
	var span: float = SPAN.get(bone_name, SPAN.get(_suffix(bone_name), 30.0))
	if span <= 0.0:
		pb.joint_type = PhysicalBone3D.JOINT_TYPE_NONE
	else:
		pb.joint_type = PhysicalBone3D.JOINT_TYPE_CONE
		if joint_limits:
			pb.set("joint_constraints/swing_span", deg_to_rad(span))        # свойство хранится в радианах
			pb.set("joint_constraints/twist_span", deg_to_rad(span * 0.6))

func _suffix(bone_name: String) -> String:
	return bone_name.trim_prefix("Left").trim_prefix("Right")

func _find_skeleton(n: Node) -> Skeleton3D:
	if n is Skeleton3D:
		return n
	for c in n.get_children():
		var s := _find_skeleton(c)
		if s:
			return s
	return null
