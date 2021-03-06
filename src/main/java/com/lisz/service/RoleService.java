package com.lisz.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.lisz.controller.ResponseStatus;
import com.lisz.entity.Role;
import com.lisz.mapper.RoleMapper;

@Service
public class RoleService {
	
	@Autowired
	private RoleMapper mapper;
	
	public List<Role> findAll() {
		return mapper.selectByExample(null);
	}

	public PageInfo<Role> findByPage(int pageNum, int pageSize) {
		PageHelper.startPage(pageNum, pageSize); // PageHelper和PageInfo是分页主要用到的工具类。
		return new PageInfo<Role>(findAll(), 10);//包装成一个PageInfo对象，解决分页的问题.可以通过第二个参数动态调整最多显示的页码数,default = 8
	}

	public ResponseStatus deleteById(int id) {
		// 1. 提示用户将要删除，不能直接删
		// 2. 回收站等通过删除标记来完成删除操作，以达到数据永远删不掉的效果 / update有的也是只增不改，多余的数据存储到别的机器或者数据库表
		int rows = mapper.deleteByPrimaryKey(id);
		if (rows == 1) {
			return new ResponseStatus(200, "OK", "Delete successfully");
		}
		return new ResponseStatus(500, "Internal error", "Delete failed.");
	}

	public Role findById(Integer id) {
		return mapper.findById(id);
	}

	public ResponseStatus add(Role role) {
		int inserted = mapper.insert(role);
		if (inserted == 1) {
			return new ResponseStatus(200, "OK", "Insertion succeeded!");
		} else {
			return new ResponseStatus(500, "Failed", "Insertion of a role failed");
		}
	}

	public ResponseStatus update(Role role) {
		int updated = mapper.updateByPrimaryKey(role);
		if (updated == 1) {
			return new ResponseStatus(200, "OK", "Update succeeded!");
		} else {
			return new ResponseStatus(500, "Failed", "Update of a role failed");
		}
	}

	public void addPermissionsForRole(int[] permissionIds, int roleId) {
		/*for (int permissionId : permissionIds) {
			mapper.addPermissionForRole(roleId, permissionId);
		}*/
		mapper.addPermissionsForRole(roleId, permissionIds);
	}

}
