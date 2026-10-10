extends Node3D
## PIXEL MECH из отдельных кирпичиков — заготовка для разборки и сборки «как конструктор».
##
## Читает паспорт кирпичей (export/pixel-mech.bricks.json) и ставит каждый кирпич отдельным
## телом RigidBody3D: коробка нужного размера и цвета плюс шипы. Пока фигурка «заморожена» —
## она стоит собранной; scatter() размораживает кирпичи и слегка толкает — фигурка рассыпается.
## По умолчанию пробел / Enter (ui_accept) рассыпает.
##
## Это наглядная основа, не финальная система: скосы здесь — коробки чуть меньше клетки,
## крепёж (болты, оси, шарниры) не входит в паспорт. Для Quest лучше рисовать кирпичи через
## MultiMeshInstance3D, а телами делать только те, что сейчас в руке или летят.

@export_file("*.json") var manifest_path := "res://pixel-mech.bricks.json"
@export var scale_m := 0.01              ## 1 единица паспорта = 1 см
@export var toggle_with_ui_accept := true

var bodies: Array[RigidBody3D] = []
var _materials := {}

func _ready() -> void:
	var text := FileAccess.get_file_as_string(manifest_path)
	var data = JSON.parse_string(text)
	if data == null or not data.has("bricks"):
		push_error("PIXEL MECH: не прочитался паспорт " + manifest_path)
		return
	var stud_mesh := CylinderMesh.new()
	stud_mesh.top_radius = 1.0
	stud_mesh.bottom_radius = 1.0
	stud_mesh.height = 1.0
	stud_mesh.radial_segments = 12
	stud_mesh.rings = 1
	for b in data["bricks"]:
		var part: Dictionary = data["parts"][b["part"]]
		var m: Array = part["matrix"]            # 4 × 4 по столбцам (как в three.js)
		var basis := Basis(Vector3(m[0], m[1], m[2]), Vector3(m[4], m[5], m[6]), Vector3(m[8], m[9], m[10]))
		var part_xf := Transform3D(basis, Vector3(m[12], m[13], m[14]))
		var c := Vector3(b["c"][0], b["c"][1], b["c"][2])
		var h := Vector3(b["h"][0], b["h"][1], b["h"][2])
		var shrink := 0.97 if b["kind"] == "brick" else 0.8
		var body := RigidBody3D.new()
		body.freeze = true
		body.transform = Transform3D(basis, (part_xf * c) * scale_m)
		body.mass = max(0.001, 8.0 * h.x * h.y * h.z * 1.0e-3)   # ~1 г на кубический сантиметр
		body.set_meta("part", b["part"])
		body.set_meta("cls", b.get("cls", ""))
		var mi := MeshInstance3D.new()
		var bm := BoxMesh.new()
		bm.size = h * 2.0 * scale_m * shrink
		mi.mesh = bm
		mi.material_override = _material(b["color"])
		body.add_child(mi)
		var cs := CollisionShape3D.new()
		var sh := BoxShape3D.new()
		sh.size = h * 2.0 * scale_m * shrink
		cs.shape = sh
		body.add_child(cs)
		for s in b.get("studs", []):
			var st := MeshInstance3D.new()
			st.mesh = stud_mesh
			st.material_override = mi.material_override
			var r: float = s[4] * scale_m
			var dir: Vector3 = [Vector3.RIGHT, Vector3.LEFT, Vector3.UP, Vector3.DOWN, Vector3.BACK, Vector3.FORWARD][int(s[3])]
			var p := (Vector3(s[0], s[1], s[2]) - c) * scale_m + dir * r * 0.35
			st.transform = Transform3D(Basis(Quaternion(Vector3.UP, dir)).scaled(Vector3(r, r * 0.7, r)), p)
			body.add_child(st)
		add_child(body)
		bodies.append(body)
	print("PIXEL MECH: кирпичей ", bodies.size(), " (", data["counts"], ")")

func _unhandled_input(event: InputEvent) -> void:
	if toggle_with_ui_accept and event.is_action_pressed("ui_accept"):
		scatter()

## Рассыпать: разморозить кирпичи и толкнуть от центра фигурки
func scatter(strength := 0.02) -> void:
	for body in bodies:
		body.freeze = false
		var away := Vector3(body.position.x, 0.0, body.position.z).normalized()
		body.apply_central_impulse((away + Vector3(randf_range(-0.3, 0.3), 0.5, randf_range(-0.3, 0.3))) * strength * body.mass)

func _material(hex: String) -> StandardMaterial3D:
	if not _materials.has(hex):
		var mat := StandardMaterial3D.new()
		mat.albedo_color = Color(hex)
		mat.roughness = 0.55
		_materials[hex] = mat
	return _materials[hex]
