extends SceneTree
## Проверка без окна: godot --headless --path <проект> --script res://check_bricks.gd
## Собирает фигурку из паспорта кирпичей, проверяет рамку, рассыпает и смотрит, что кирпичи упали на пол.

var node: Node3D
var frames := 0
var t0 := 0

func _initialize() -> void:
	pass

func _physics_process(_delta: float) -> bool:
	frames += 1
	if frames == 1:
		var floor := StaticBody3D.new()
		var fs := CollisionShape3D.new()
		var box := BoxShape3D.new()
		box.size = Vector3(6, 0.2, 6)
		fs.shape = box
		floor.add_child(fs)
		floor.position = Vector3(0, -0.1, 0)
		root.add_child(floor)
		node = Node3D.new()
		node.set_script(load("res://pixel_mech_bricks.gd"))
		root.add_child(node)
		var lo := Vector3(INF, INF, INF)
		var hi := -lo
		for b in node.bodies:
			lo = lo.min(b.global_position)
			hi = hi.max(b.global_position)
		print("собрано: центры кирпичей от ", lo, " до ", hi, " (рост ≈ ", snapped(hi.y - lo.y, 0.001), " м)")
		return false
	if frames == 3:
		node.scatter()
		t0 = Time.get_ticks_msec()
		return false
	if frames == 300:
		var top := 0.0
		var bad := 0
		for b in node.bodies:
			var p: Vector3 = b.global_position
			if not p.is_finite() or p.y < -0.05:
				bad += 1
			top = max(top, p.y)
		var ms := float(Time.get_ticks_msec() - t0) / (frames - 3)
		print("ИТОГ кирпичи: ", node.bodies.size(), " шт. · выше всех теперь ", snapped(top, 0.001), " м · провалились/сломались: ", bad, " · ", snapped(ms, 0.1), " мс на шаг физики")
		node.free()
		return true
	return false
